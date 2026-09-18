package com.llf.ai.cases.react;

import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.api.dto.RuntimeModelConfigDTO;
import com.llf.ai.cases.IAIAgentReActServiceCase;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.cases.react.node.RootNode;
import com.llf.ai.domain.agent.model.entity.ChatSessionEntity;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.matter.tools.CommandApprovalService;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionObserverRegistry;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelScope;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelService;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import com.llf.ai.domain.db.service.session.DbSessionService;
import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.concurrent.CancellationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 智能体 ReAct 执行服务实现
 *
 * <p>职责：
 * - 流式对话（SSE）：创建 emitter → 创建动态上下文 → 走节点链路
 * - 普通对话（非流式）：直接调用节点链路
 *
 * <p>节点链路：
 * RootNode → AiCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author llf
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    private static final long HEARTBEAT_INTERVAL_MILLIS = 15 * 1000L;
    private static final String AGENT_INITIALIZATION_FAILURE_MESSAGE = "智能体初始化失败，请稍后重试。";
    private static final String AGENT_EXECUTION_FAILURE_MESSAGE = "智能体执行失败，请稍后重试。";

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Resource
    private IChatService chatService;

    @Resource
    private RuntimeChatModelService runtimeChatModelService;

    @Resource
    private ReActStreamEventPublisher streamEventPublisher;

    @Resource
    private CommandApprovalService commandApprovalService;

    @Resource
    private ISshTerminalService sshTerminalService;

    @Resource
    private DbSessionService dbSessionService;

    @Value("${crowssh.db.enabled:false}")
    private boolean databaseEnabled;

    @Resource(name = "sseStreamExecutor")
    private ExecutorService streamExecutor;

    @Resource(name = "sseHeartbeatScheduler")
    private ScheduledExecutorService heartbeatScheduler;

    @Resource
    private com.llf.ai.domain.agent.service.armory.matter.tools.DynamicAgentOrchestrator subAgentOrchestrator;

    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        String registeredSessionId = null;
        Runnable registeredCancel = null;
        ActiveStream registeredStream = null;

        try {
            String sessionId = ensureSession(requestDTO);
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    sessionId, requestDTO.getTerminalSessionId());

            AtomicBoolean streamActive = new AtomicBoolean(true);
            AtomicBoolean responseCompleted = new AtomicBoolean(false);
            AtomicBoolean streamStarted = new AtomicBoolean(false);
            AtomicReference<Future<?>> streamFutureRef = new AtomicReference<>();
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .sessionId(sessionId)
                    .emitter(emitter)
                    .streaming(true)
                    .streamActive(streamActive)
                    .build();

            Runnable cancelStream = () -> {
                cancelStreamInternal(
                    streamActive,
                    streamFutureRef,
                    requestDTO.getUserId(),
                    sessionId,
                    requestDTO.getTerminalSessionId(), dynamicContext);
                synchronized (dynamicContext) {
                    if (!streamStarted.get()) {
                        activeStreams.computeIfPresent(sessionId, (id, value) ->
                                value.context() == dynamicContext ? null : value);
                        requestDTO.clearRuntimeSecret();
                    }
                }
            };
            ActiveStream activeStream = new ActiveStream(
                    requestDTO.getUserId(), requestDTO.getTerminalSessionId(), dynamicContext, cancelStream);
            if (activeStreams.putIfAbsent(sessionId, activeStream) != null) {
                throw new IllegalStateException("该 AI 会话已有正在运行的流式请求");
            }
            registeredSessionId = sessionId;
            registeredStream = activeStream;
            registeredCancel = cancelStream;
            if (requestDTO.isDatabaseRequest()) {
                // 与取消共用上下文锁，取消先到时不再登记新轮次。
                synchronized (dynamicContext) {
                    if (!streamActive.get()) throw new CancellationException();
                    dynamicContext.setDatabaseBinding(dbSessionService.beginAiTurn(requestDTO.getUserId(),
                            requestDTO.getDbSessionId(), sessionId, java.util.UUID.randomUUID().toString()));
                }
            }
            registerEmitterCallbacks(
                    emitter,
                    sessionId,
                    streamActive,
                    responseCompleted,
                    cancelStream
            );

            Runnable streamTask = createStreamTask(
                    requestDTO,
                    dynamicContext,
                    responseCompleted,
                    streamStarted,
                    cancelStream
            );
            streamFutureRef.set(streamExecutor.submit(streamTask));
            if (!streamActive.get()) streamFutureRef.get().cancel(true);

        } catch (Exception e) {
            Throwable cause = e.getCause();
            log.error("ReAct 流式对话初始化失败: exceptionType={} message={} causeType={}",
                    e.getClass().getName(), e.getMessage(),
                    cause == null ? "none" : cause.getClass().getName(), e);
            if (registeredSessionId != null) {
                if (registeredCancel != null) registeredCancel.run();
                activeStreams.remove(registeredSessionId, registeredStream);
            }
            requestDTO.clearRuntimeSecret();
            emitter.completeWithError(new IllegalStateException(AGENT_INITIALIZATION_FAILURE_MESSAGE));
        }

        return emitter;
    }

    private Runnable createStreamTask(
            ChatRequestDTO requestDTO,
            DefaultReActFactory.DynamicContext dynamicContext,
            AtomicBoolean responseCompleted,
            AtomicBoolean streamStarted,
            Runnable cancelStream
    ) {
        String sessionId = dynamicContext.getSessionId();
        return () -> {
            synchronized (dynamicContext) {
                if (!dynamicContext.getStreamActive().get()) return;
                streamStarted.set(true);
            }
            ScheduledFuture<?> heartbeatFuture = null;
            ToolExecutionObserverRegistry.Observer executionObserver = executionEvent -> {
                dynamicContext.recordToolExecutionEvent(executionEvent);
                if (!dynamicContext.getStreamActive().get()) {
                    return;
                }
                try {
                    streamEventPublisher.sendExecutionEvent(dynamicContext, executionEvent);
                } catch (Exception e) {
                    log.warn("工具执行事件发送失败: toolCallId={}, exceptionType={}",
                            executionEvent.getToolCallId(), e.getClass().getName());
                    cancelStream.run();
                }
            };
            if (dynamicContext.getDatabaseBinding() == null) {
                ToolExecutionObserverRegistry.registerApprovalObserver(sessionId, executionObserver);
            } else if (requestDTO.isSupportsDbApproval()) {
                ToolExecutionObserverRegistry.registerDbApprovalObserver(sessionId, executionObserver);
            } else {
                ToolExecutionObserverRegistry.register(sessionId, executionObserver);
            }

            try (RuntimeChatModelScope ignored = runtimeChatModelService.open(
                    toRuntimeModelConfig(requestDTO.getRuntimeModel()))) {
                heartbeatFuture = startHeartbeat(dynamicContext, cancelStream);
                streamEventPublisher.sendStatus(dynamicContext, "running", "正在处理请求");
                ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);

                if (!dynamicContext.getStreamActive().get()) {
                    return;
                }

                if ("error".equals(result.getStopReason())) {
                    streamEventPublisher.sendError(dynamicContext);
                } else {
                    streamEventPublisher.sendStatus(dynamicContext, "success", "处理完成");
                    streamEventPublisher.sendDone(dynamicContext, result);
                }

                responseCompleted.set(true);
                dynamicContext.getStreamActive().set(false);
                dynamicContext.getEmitter().complete();
                log.info("ReAct 流式对话完成 - 步数:{}, 工具调用:{}, stopReason:{}",
                        result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
            } catch (Exception e) {
                boolean cancelled = !dynamicContext.getStreamActive().get()
                        || Thread.currentThread().isInterrupted()
                        || e instanceof CancellationException;
                if (cancelled) {
                    log.info("ReAct 流式对话已终止 sessionId={}", sessionId);
                } else {
                    log.error("ReAct 流式对话异常 sessionId={} exceptionType={}",
                            sessionId, e.getClass().getName());
                    try {
                        streamEventPublisher.sendError(dynamicContext);
                    } catch (Exception sendError) {
                        log.debug("ReAct 错误事件发送失败: exceptionType={}",
                                sendError.getClass().getName());
                    }
                    responseCompleted.set(true);
                    dynamicContext.getEmitter().complete();
                }
            } finally {
                dynamicContext.getStreamActive().set(false);
                stopDatabaseTurn(dynamicContext.getDatabaseBinding());
                if (heartbeatFuture != null) heartbeatFuture.cancel(false);
                ToolExecutionObserverRegistry.unregister(sessionId, executionObserver);
                activeStreams.computeIfPresent(sessionId, (id, value) ->
                        value.context() == dynamicContext ? null : value);
                requestDTO.clearRuntimeSecret();
            }
        };
    }

    @Override
    public boolean cancelStream(String ownerId, String sessionId, String terminalSessionId) {
        if (ownerId == null || ownerId.isBlank()
                || sessionId == null || sessionId.isBlank()
                || terminalSessionId == null || terminalSessionId.isBlank()) {
            throw new IllegalArgumentException("取消请求缺少会话信息");
        }
        ActiveStream activeStream = activeStreams.get(sessionId);
        if (activeStream == null) {
            return false;
        }
        if (!activeStream.ownerId().equals(ownerId)
                || activeStream.context().getDatabaseBinding() != null
                || !java.util.Objects.equals(activeStream.terminalSessionId(), terminalSessionId)) {
            throw new IllegalArgumentException("无权取消该 AI 流式会话");
        }
        activeStream.cancel().run();
        return true;
    }

    @Override
    public boolean cancelDatabaseStream(String ownerId, String sessionId, String dbSessionId, String turnId) {
        return "CANCEL_REQUESTED".equals(cancelDatabaseStreamStatus(ownerId, sessionId, dbSessionId, turnId).streamState());
    }

    @Override
    public com.llf.ai.api.dto.ChatStreamCancelResponseDTO cancelDatabaseStreamStatus(
            String ownerId, String sessionId, String dbSessionId, String turnId) {
        if (ownerId == null || ownerId.isBlank() || sessionId == null || sessionId.isBlank()
                || dbSessionId == null || dbSessionId.isBlank() || turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("取消请求缺少数据库会话或轮次");
        }
        ActiveStream stream = activeStreams.get(sessionId);
        if (stream == null) return new com.llf.ai.api.dto.ChatStreamCancelResponseDTO("NOT_ACTIVE", java.util.List.of());
        DbResourceBinding capturedBinding;
        synchronized (stream.context()) {
            DbResourceBinding binding = stream.context().getDatabaseBinding();
            if (!ownerId.equals(stream.ownerId()) || binding == null || !dbSessionId.equals(binding.dbSessionId())) {
                throw new IllegalArgumentException("无权取消该数据库 AI 流式会话");
            }
            if (!turnId.equals(binding.turnId())) return new com.llf.ai.api.dto.ChatStreamCancelResponseDTO("NOT_ACTIVE", java.util.List.of());
            capturedBinding = binding;
        }
        stream.cancel().run();
        java.util.List<com.llf.ai.api.dto.ChatStreamCancelResponseDTO.Execution> executions =
                dbSessionService.aiTurnExecutions(capturedBinding).stream()
                    // stopAiTurn 只标记当时未结束的执行；同轮已成功的历史不属于取消目标。
                    .filter(com.llf.ai.domain.db.model.entity.DbExecutionEntity::isCancelRequested)
                    .map(execution -> new com.llf.ai.api.dto.ChatStreamCancelResponseDTO.Execution(
                            execution.getExecutionId(), execution.getState().name(),
                            execution.getOutcome() == null ? null : execution.getOutcome().name(), execution.isCancelRequested()))
                    .toList();
        return new com.llf.ai.api.dto.ChatStreamCancelResponseDTO("CANCEL_REQUESTED", executions);
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try (RuntimeChatModelScope ignored = openOptionalRuntimeModel(requestDTO.getRuntimeModel())) {
            if (requestDTO.isDatabaseRequest()) throw new IllegalArgumentException("数据库对话需要流式事件通道");
            String sessionId = ensureSession(requestDTO);
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .sessionId(sessionId)
                    .streaming(false)
                    .build();
            ToolExecutionObserverRegistry.Observer executionObserver =
                    dynamicContext::recordToolExecutionEvent;
            ToolExecutionObserverRegistry.register(sessionId, executionObserver);

            try {
                ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
                return result.getContent();
            } finally {
                ToolExecutionObserverRegistry.unregister(sessionId, executionObserver);
            }

        } catch (Exception e) {
            log.error("ReAct 普通对话异常: exceptionType={} message={}", e.getClass().getName(), e.getMessage(), e);
            return AGENT_EXECUTION_FAILURE_MESSAGE;
        } finally {
            requestDTO.clearRuntimeSecret();
        }
    }

    private String ensureSession(ChatRequestDTO requestDTO) {
        requestDTO.validateResourceBinding();
        if (requestDTO.isDatabaseRequest()) {
            if (!databaseEnabled) throw new IllegalArgumentException("数据库功能尚未启用");
            String sessionId = chatService.resolveDatabaseSession(requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getDbConnectionId(), requestDTO.getDbSessionId());
            requestDTO.setSessionId(sessionId);
            return sessionId;
        }
        String sessionId = chatService.resolveSession(
                requestDTO.getAgentId(),
                requestDTO.getUserId(),
                requestDTO.getSessionId(),
                requestDTO.getConnectionId(),
                requestDTO.getTerminalSessionId()
        );
        requestDTO.setSessionId(sessionId);
        ChatSessionEntity sessionContext = chatService.getSessionContext(
                requestDTO.getAgentId(), requestDTO.getUserId(), sessionId);
        if (sessionContext != null) {
            if (requestDTO.getConnectionId() == null || requestDTO.getConnectionId().isBlank()) {
                requestDTO.setConnectionId(sessionContext.getConnectionId());
            }
            if (requestDTO.getTerminalSessionId() == null || requestDTO.getTerminalSessionId().isBlank()) {
                requestDTO.setTerminalSessionId(sessionContext.getTerminalSessionId());
            }
        }
        return sessionId;
    }

    private void registerEmitterCallbacks(
            ResponseBodyEmitter emitter,
            String sessionId,
            AtomicBoolean streamActive,
            AtomicBoolean responseCompleted,
            Runnable cancelStream
    ) {
        emitter.onTimeout(() -> {
            log.warn("ReAct 流式对话超时，终止后台任务 sessionId={}", sessionId);
            cancelStream.run();
        });
        emitter.onError(error -> {
            if (!responseCompleted.get() && streamActive.get()) {
                log.warn("ReAct 流式连接异常，终止后台任务 sessionId={} exceptionType={}",
                        sessionId, error.getClass().getName());
                cancelStream.run();
            }
        });
        emitter.onCompletion(() -> {
            if (!responseCompleted.get() && streamActive.get()) {
                log.warn("ReAct 流式连接提前结束，终止后台任务 sessionId={}", sessionId);
                cancelStream.run();
            }
        });
    }

    private void cancelStreamInternal(
            AtomicBoolean streamActive,
            AtomicReference<Future<?>> streamFutureRef,
            String ownerId,
            String agentSessionId,
            String terminalSessionId,
            DefaultReActFactory.DynamicContext context
    ) {
        DbResourceBinding databaseBinding;
        synchronized (context) {
            if (!streamActive.getAndSet(false)) return;
            databaseBinding = context.getDatabaseBinding();
        }
        if (subAgentOrchestrator != null) subAgentOrchestrator.cancel(ownerId, agentSessionId);
        try {
            if (databaseBinding != null) {
                stopDatabaseTurn(databaseBinding);
            } else if (terminalSessionId != null) {
                commandApprovalService.cancelSession(ownerId, agentSessionId);
                try {
                    sshTerminalService.cancelCommand(ownerId, terminalSessionId);
                } catch (IllegalArgumentException e) {
                    log.debug("取消 AI SSH 命令时终端已结束 sessionId={} terminalSessionId={}",
                            agentSessionId, terminalSessionId);
                }
            }
        } finally {
            try {
                Future<?> streamFuture = streamFutureRef.get();
                if (streamFuture != null) streamFuture.cancel(true);
            } finally {
                // 主动停止也必须结束无限超时的响应；不能只停止后台任务。
                context.getEmitter().complete();
            }
        }
    }

    private void stopDatabaseTurn(DbResourceBinding binding) {
        if (binding == null) return;
        try {
            dbSessionService.stopAiTurn(binding, commandApprovalService.databaseApprovals());
        } catch (Exception e) {
            log.warn("数据库 AI 轮次清理失败 sessionId={} exceptionType={}", binding.agentSessionId(), e.getClass().getName());
        } finally {
            dbSessionService.endAiTurn(binding);
        }
    }

    private ScheduledFuture<?> startHeartbeat(
            DefaultReActFactory.DynamicContext dynamicContext,
            Runnable cancelStream
    ) {
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!dynamicContext.getStreamActive().get()) {
                return;
            }
            try {
                streamEventPublisher.sendHeartbeat(dynamicContext);
            } catch (Exception e) {
                cancelStream.run();
            }
        }, HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    private RuntimeChatModelScope openOptionalRuntimeModel(RuntimeModelConfigDTO runtimeModel) {
        return runtimeModel == null
                ? null
                : runtimeChatModelService.open(toRuntimeModelConfig(runtimeModel));
    }

    private RuntimeModelConfig toRuntimeModelConfig(RuntimeModelConfigDTO runtimeModel) {
        if (runtimeModel == null) {
            throw new IllegalArgumentException("请先在客户端配置 AI 模型和 API Key");
        }
        return new RuntimeModelConfig(
                runtimeModel.getProvider(),
                runtimeModel.getProtocol(),
                runtimeModel.getBaseUrl(),
                runtimeModel.getApiKey(),
                runtimeModel.getAuthType(),
                runtimeModel.getAuthHeader(),
                runtimeModel.getAuthPrefix(),
                runtimeModel.getModelListPath(),
                runtimeModel.getModel(),
                runtimeModel.getTemperature(),
                runtimeModel.getOmitTemperature(),
                runtimeModel.getTokenParameter(),
                runtimeModel.getMaxTokens()
        );
    }

    private record ActiveStream(String ownerId, String terminalSessionId,
                                DefaultReActFactory.DynamicContext context, Runnable cancel) {
    }
}
