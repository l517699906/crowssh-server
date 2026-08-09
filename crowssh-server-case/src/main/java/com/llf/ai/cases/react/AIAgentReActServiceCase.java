package com.llf.ai.cases.react;

import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.api.dto.RuntimeModelConfigDTO;
import com.llf.ai.cases.IAIAgentReActServiceCase;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.cases.react.node.RootNode;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.matter.tools.CommandApprovalService;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionObserverRegistry;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelScope;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelService;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.concurrent.CancellationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final long STREAM_TIMEOUT_MILLIS = 10 * 60 * 1000L;
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

    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(STREAM_TIMEOUT_MILLIS);
        String registeredSessionId = null;

        try {
            String sessionId = ensureSession(requestDTO);
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    sessionId, requestDTO.getTerminalSessionId());

            AtomicBoolean streamActive = new AtomicBoolean(true);
            AtomicBoolean responseCompleted = new AtomicBoolean(false);
            AtomicReference<Thread> streamThreadRef = new AtomicReference<>();
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .sessionId(sessionId)
                    .emitter(emitter)
                    .streaming(true)
                    .streamActive(streamActive)
                    .build();

            Runnable cancelStream = () -> cancelStreamInternal(
                    streamActive,
                    streamThreadRef,
                    requestDTO.getUserId(),
                    sessionId,
                    requestDTO.getTerminalSessionId());
            ActiveStream activeStream = new ActiveStream(
                    requestDTO.getUserId(), requestDTO.getTerminalSessionId(), cancelStream);
            if (activeStreams.putIfAbsent(sessionId, activeStream) != null) {
                throw new IllegalStateException("该 AI 会话已有正在运行的流式请求");
            }
            registeredSessionId = sessionId;
            registerEmitterCallbacks(
                    emitter,
                    sessionId,
                    streamActive,
                    responseCompleted,
                    cancelStream
            );

            Thread streamThread = createStreamThread(
                    requestDTO,
                    dynamicContext,
                    responseCompleted,
                    cancelStream
            );
            streamThreadRef.set(streamThread);
            streamThread.start();

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败: exceptionType={}", e.getClass().getName());
            if (registeredSessionId != null) {
                activeStreams.remove(registeredSessionId);
            }
            requestDTO.clearRuntimeSecret();
            emitter.completeWithError(new IllegalStateException(AGENT_INITIALIZATION_FAILURE_MESSAGE));
        }

        return emitter;
    }

    private Thread createStreamThread(
            ChatRequestDTO requestDTO,
            DefaultReActFactory.DynamicContext dynamicContext,
            AtomicBoolean responseCompleted,
            Runnable cancelStream
    ) {
        String sessionId = dynamicContext.getSessionId();
        Thread streamThread = new Thread(() -> {
            Thread heartbeatThread = startHeartbeatThread(dynamicContext, cancelStream);
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
            ToolExecutionObserverRegistry.register(sessionId, executionObserver);

            try (RuntimeChatModelScope ignored = runtimeChatModelService.open(
                    toRuntimeModelConfig(requestDTO.getRuntimeModel()))) {
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
                heartbeatThread.interrupt();
                ToolExecutionObserverRegistry.unregister(sessionId, executionObserver);
                activeStreams.remove(sessionId);
                requestDTO.clearRuntimeSecret();
            }
        }, "react-stream-" + sessionId);
        streamThread.setDaemon(true);
        return streamThread;
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
                || !activeStream.terminalSessionId().equals(terminalSessionId)) {
            throw new IllegalArgumentException("无权取消该 AI 流式会话");
        }
        activeStream.cancel().run();
        return true;
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try (RuntimeChatModelScope ignored = openOptionalRuntimeModel(requestDTO.getRuntimeModel())) {
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
            log.error("ReAct 普通对话异常: exceptionType={}", e.getClass().getName());
            return AGENT_EXECUTION_FAILURE_MESSAGE;
        } finally {
            requestDTO.clearRuntimeSecret();
        }
    }

    private String ensureSession(ChatRequestDTO requestDTO) {
        String sessionId = chatService.resolveSession(
                requestDTO.getAgentId(),
                requestDTO.getUserId(),
                requestDTO.getSessionId(),
                requestDTO.getConnectionId(),
                requestDTO.getTerminalSessionId()
        );
        requestDTO.setSessionId(sessionId);
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
            AtomicReference<Thread> streamThreadRef,
            String ownerId,
            String agentSessionId,
            String terminalSessionId
    ) {
        if (!streamActive.getAndSet(false)) {
            return;
        }
        commandApprovalService.cancelSession(ownerId, agentSessionId);
        try {
            sshTerminalService.cancelCommand(ownerId, terminalSessionId);
        } catch (IllegalArgumentException e) {
            log.debug("取消 AI SSH 命令时终端已结束 sessionId={} terminalSessionId={}",
                    agentSessionId, terminalSessionId);
        }
        Thread streamThread = streamThreadRef.get();
        if (streamThread != null && streamThread != Thread.currentThread()) {
            streamThread.interrupt();
        }
    }

    private Thread startHeartbeatThread(
            DefaultReActFactory.DynamicContext dynamicContext,
            Runnable cancelStream
    ) {
        Thread heartbeatThread = new Thread(() -> {
            try {
                while (dynamicContext.getStreamActive().get()) {
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                    if (!dynamicContext.getStreamActive().get()) {
                        break;
                    }
                    streamEventPublisher.sendHeartbeat(dynamicContext);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                cancelStream.run();
            }
        }, "sse-heartbeat-" + dynamicContext.getSessionId());
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
        return heartbeatThread;
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

    private record ActiveStream(String ownerId, String terminalSessionId, Runnable cancel) {
    }
}
