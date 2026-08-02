package com.llf.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.llf.ai.api.IAgentService;
import com.llf.ai.api.dto.*;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelScope;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelService;
import com.llf.ai.types.enums.ResponseCode;
import com.llf.ai.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private RuntimeChatModelService runtimeChatModelService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{} connectionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId());
            String sessionId = chatService.createSession(
                    requestDTO.getAgentId(),
                    requestDTO.getUserId(),
                    requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId()
            );

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(
                        requestDTO.getAgentId(),
                        requestDTO.getUserId(),
                        requestDTO.getConnectionId(),
                        requestDTO.getTerminalSessionId()
                );
            }

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * AI 流式事件接口；你现在看到的 trigger 层接口里的逻辑比较多，而后续 case 的加入，在架构设计上，就是来分摊 trigger 层对于逻辑编排的压力的。让 trigger 的 http 处理更为简洁，只是入参、出参的处理，以及异常的封装。
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        // MVP 简化版：直接使用 ChatService.handleMessageStream() 转发 SSE 事件
        // 将 ADK 事件流转为结构化 JSON SSE 事件，前端可区分文本和工具结果
        try {
            log.info("MVP 流式对话 agentId:{} userId:{} sessionId:{} connectionId:{} terminalSessionId:{} messageLength:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(),
                    requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId(),
                    requestDTO.getMessage() == null ? 0 : requestDTO.getMessage().length());

            // 如果未指定 sessionId，先创建
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(
                        requestDTO.getAgentId(),
                        requestDTO.getUserId(),
                        requestDTO.getConnectionId(),
                        requestDTO.getTerminalSessionId()
                );
            }

            // 创建 SSE 发射器（10 分钟超时，AI + SSH 命令执行可能需要较长时间）
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);

            String terminalSessionId = requestDTO.getTerminalSessionId();
            String finalSessionId = sessionId;
            AtomicBoolean streamActive = new AtomicBoolean(true);
            AtomicBoolean responseCompleted = new AtomicBoolean(false);
            AtomicReference<Thread> streamThreadRef = new AtomicReference<>();

            Runnable cancelStream = () -> {
                if (!streamActive.getAndSet(false)) {
                    return;
                }
                Thread streamThread = streamThreadRef.get();
                if (streamThread != null && streamThread != Thread.currentThread()) {
                    streamThread.interrupt();
                }
            };

            emitter.onTimeout(() -> {
                log.warn("MVP 流式对话超时，终止后台任务 sessionId={}", finalSessionId);
                cancelStream.run();
            });
            emitter.onError(error -> {
                if (!responseCompleted.get() && streamActive.get()) {
                    log.warn("MVP 流式连接异常，终止后台任务 sessionId={} reason={}",
                            finalSessionId, error.getMessage());
                    cancelStream.run();
                }
            });
            emitter.onCompletion(() -> {
                if (!responseCompleted.get() && streamActive.get()) {
                    log.warn("MVP 流式连接提前结束，终止后台任务 sessionId={}", finalSessionId);
                    cancelStream.run();
                }
            });

            // 异步执行，避免阻塞 HTTP 线程
            Thread streamThread = new Thread(() -> {
                ObjectMapper objectMapper = new ObjectMapper();
                StreamEventWriter eventWriter = new StreamEventWriter(
                        emitter, objectMapper, finalSessionId);
                SshExecuteAdkTool.ExecutionObserver executionObserver = executionEvent -> {
                    if (!streamActive.get()) {
                        return;
                    }
                    try {
                        String eventType = "running".equals(executionEvent.getStatus())
                                ? "tool_call"
                                : "tool_result";
                        Map<String, Object> toolEvent = eventWriter.newEvent(eventType);
                        toolEvent.put("toolCallId", executionEvent.getToolCallId());
                        toolEvent.put("toolName", executionEvent.getToolName());
                        toolEvent.put("command", executionEvent.getCommand());
                        toolEvent.put("status", executionEvent.getStatus());
                        toolEvent.put("startedAt", executionEvent.getStartedAt());

                        if ("tool_result".equals(eventType)) {
                            toolEvent.put("completedAt", executionEvent.getCompletedAt());
                            toolEvent.put("durationMs", executionEvent.getDurationMs());
                            toolEvent.put("outputLength", executionEvent.getOutputLength());
                            toolEvent.put("content", "success".equals(executionEvent.getStatus())
                                    ? "命令执行完成，完整输出已保留在终端中。"
                                    : executionEvent.getErrorMessage());
                            if (executionEvent.getErrorMessage() != null) {
                                toolEvent.put("errorMessage", executionEvent.getErrorMessage());
                            }
                        }

                        eventWriter.send(toolEvent);
                    } catch (Exception e) {
                        log.warn("工具执行事件发送失败: toolCallId={}, reason={}",
                                executionEvent.getToolCallId(), e.getMessage());
                        cancelStream.run();
                    }
                };
                SshExecuteAdkTool.setExecutionObserver(finalSessionId, executionObserver);

                // 心跳保活线程：在 AI 处理期间定期发送 SSE 注释行，防止浏览器/proxy 超时断开
                Thread heartbeatThread = new Thread(() -> {
                    try {
                        while (streamActive.get()) {
                            Thread.sleep(15000); // 每 15 秒发一次心跳
                            if (!streamActive.get()) {
                                break;
                            }
                            try {
                                emitter.send(": heartbeat\n\n");
                            } catch (Exception e) {
                                cancelStream.run();
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        // 正常退出
                    }
                }, "sse-heartbeat-" + finalSessionId);
                heartbeatThread.setDaemon(true);
                heartbeatThread.start();

                try (RuntimeChatModelScope ignored = runtimeChatModelService.open(
                        RuntimeModelConfigMapper.from(requestDTO.getRuntimeModel()))) {
                    Map<String, Object> statusEvent = eventWriter.newEvent("status");
                    statusEvent.put("status", "running");
                    statusEvent.put("content", "正在处理请求");
                    eventWriter.send(statusEvent);

                    // 调用 ChatService 获取事件流
                    Flowable<Event> events = chatService.handleMessageStream(
                            requestDTO.getAgentId(),
                            requestDTO.getUserId(),
                            finalSessionId,
                            requestDTO.getMessage(),
                            terminalSessionId,
                            requestDTO.getConnectionId()
                    );

                    StringBuilder textAccumulator = new StringBuilder();

                    // 遍历事件流，转发为结构化 SSE JSON 事件
                    events.blockingForEach(event -> {
                        if (!streamActive.get() || Thread.currentThread().isInterrupted()) {
                            throw new CancellationException("流式连接已结束");
                        }
                        try {
                            // 1. 处理文本内容（AI 的回复文本）
                            String eventText = extractTextContent(event);
                            if (!eventText.isBlank()) {
                                textAccumulator.append(eventText);

                                Map<String, Object> textEvent = eventWriter.newEvent("text");
                                textEvent.put("content", eventText);
                                textEvent.put("fullText", textAccumulator.toString());
                                eventWriter.send(textEvent);
                                log.info("SSE text 事件已发送: length={}, fullTextLength={}, turnComplete={}",
                                        eventText.length(), textAccumulator.length(), event.turnComplete());
                            }

                        } catch (Exception ex) {
                            cancelStream.run();
                            throw new CancellationException("SSE 发送异常: " + ex.getMessage());
                        }
                    });

                    if (!streamActive.get()) {
                        return;
                    }

                    Map<String, Object> completedStatusEvent = eventWriter.newEvent("status");
                    completedStatusEvent.put("status", "success");
                    completedStatusEvent.put("content", "处理完成");
                    eventWriter.send(completedStatusEvent);

                    // 发送 done 事件
                    Map<String, Object> doneEvent = eventWriter.newEvent("done");
                    doneEvent.put("content", textAccumulator.toString());
                    doneEvent.put("stopReason", "completed");
                    eventWriter.send(doneEvent);

                    responseCompleted.set(true);
                    streamActive.set(false);
                    emitter.complete();
                    log.info("MVP 流式对话完成 sessionId={}", finalSessionId);

                } catch (Exception e) {
                    boolean cancelled = !streamActive.get()
                            || Thread.currentThread().isInterrupted()
                            || e instanceof CancellationException;
                    streamActive.set(false);
                    if (cancelled) {
                        log.info("MVP 流式对话已终止 sessionId={}", finalSessionId);
                    } else {
                        log.error("MVP 流式对话异常", e);
                        try {
                            Map<String, Object> errorEvent = eventWriter.newEvent("error");
                            errorEvent.put("content", e.getMessage() == null ? "智能体执行失败" : e.getMessage());
                            errorEvent.put("code", "AGENT_STREAM_ERROR");
                            errorEvent.put("retryable", false);
                            eventWriter.send(errorEvent);
                        } catch (Exception ignored) {
                        }
                        responseCompleted.set(true);
                        emitter.complete();
                    }
                } finally {
                    streamActive.set(false);
                    heartbeatThread.interrupt();
                    SshExecuteAdkTool.clearExecutionObserver(finalSessionId, executionObserver);
                    requestDTO.clearRuntimeSecret();
                }
            }, "mvp-stream-" + sessionId);
            streamThreadRef.set(streamThread);
            streamThread.start();

            return emitter;

        } catch (Exception e) {
            log.error("MVP 流式对话初始化失败", e);
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            emitter.completeWithError(e);
            return emitter;
        }
    }

    private static String extractTextContent(Event event) {
        return event.content()
                .flatMap(content -> content.parts())
                .stream()
                .flatMap(List::stream)
                .map(part -> part.text().orElse(""))
                .collect(Collectors.joining());
    }

    private static final class StreamEventWriter {
        private final ResponseBodyEmitter emitter;
        private final ObjectMapper objectMapper;
        private final String sessionId;
        private long sequence;

        private StreamEventWriter(
                ResponseBodyEmitter emitter,
                ObjectMapper objectMapper,
                String sessionId) {
            this.emitter = emitter;
            this.objectMapper = objectMapper;
            this.sessionId = sessionId;
        }

        private Map<String, Object> newEvent(String eventType) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", eventType);
            return event;
        }

        private synchronized void send(Map<String, Object> event) throws Exception {
            long nextSequence = ++sequence;
            event.put("schemaVersion", 2);
            event.put("eventId", sessionId + ":" + nextSequence);
            event.put("sequence", nextSequence);
            event.put("timestamp", System.currentTimeMillis());
            event.put("sessionId", sessionId);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
        }
    }
}
