package com.llf.ai.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.llf.ai.api.IAgentService;
import com.llf.ai.api.dto.*;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
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
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

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
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

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
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
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
            log.info("MVP 流式对话 agentId:{} userId:{} sessionId:{} terminalSessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(),
                    requestDTO.getTerminalSessionId(), requestDTO.getMessage());

            // 如果未指定 sessionId，先创建
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            // 创建 SSE 发射器（10 分钟超时，AI + SSH 命令执行可能需要较长时间）
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);

            // 绑定终端会话到 ThreadLocal（核心！工具从这里取 terminalSessionId）
            String terminalSessionId = requestDTO.getTerminalSessionId();
            if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
                SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            }

            // 异步执行，避免阻塞 HTTP 线程
            String finalSessionId = sessionId;
            new Thread(() -> {
                ObjectMapper objectMapper = new ObjectMapper();
                // 心跳保活线程：在 AI 处理期间定期发送 SSE 注释行，防止浏览器/proxy 超时断开
                java.util.concurrent.atomic.AtomicBoolean streaming = new java.util.concurrent.atomic.AtomicBoolean(true);
                Thread heartbeatThread = new Thread(() -> {
                    try {
                        while (streaming.get()) {
                            Thread.sleep(15000); // 每 15 秒发一次心跳
                            try {
                                emitter.send(": heartbeat\n\n");
                            } catch (Exception e) {
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        // 正常退出
                    }
                }, "sse-heartbeat-" + finalSessionId);
                heartbeatThread.setDaemon(true);
                heartbeatThread.start();

                try {
                    // 调用 ChatService 获取事件流
                    Flowable<Event> events = chatService.handleMessageStream(
                            requestDTO.getAgentId(),
                            requestDTO.getUserId(),
                            finalSessionId,
                            requestDTO.getMessage(),
                            terminalSessionId
                    );

                    StringBuilder textAccumulator = new StringBuilder();

                    // 遍历事件流，转发为结构化 SSE JSON 事件
                    events.blockingForEach(event -> {
                        try {
                            event.stringifyContent();

                            // 1. 处理文本内容（AI 的回复文本）
                            String eventText = event.stringifyContent();
                            if (!eventText.isBlank()) {
                                textAccumulator.append(eventText);

                                Map<String, Object> textEvent = new HashMap<>();
                                textEvent.put("event", "text");
                                textEvent.put("content", eventText);
                                textEvent.put("fullText", textAccumulator.toString());
                                emitter.send(objectMapper.writeValueAsString(textEvent) + "\n");
                                log.info("SSE text 事件已发送: length={}, fullTextLength={}, turnComplete={}",
                                        eventText.length(), textAccumulator.length(), event.turnComplete());
                            }

                            // 2. 从 stateDelta 检测工具执行结果（SSH 命令输出）
                            EventActions actions = event.actions();
                            if (actions != null) {
                                Map<String, Object> stateDelta = actions.stateDelta();
                                if (stateDelta != null && !stateDelta.isEmpty()) {
                                    for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
                                        String stateKey = entry.getKey();
                                        Object stateValue = entry.getValue();
                                        if ("REMOVED".equals(stateValue)) continue;

                                        String resultContent = stateValue != null ? stateValue.toString() : "";
                                        // 去除 JSON 字符串外层引号号
                                        if (resultContent.startsWith("\"") && resultContent.endsWith("\"") && resultContent.length() > 1) {
                                            resultContent = resultContent.substring(1, resultContent.length() - 1);
                                        }

                                        String toolCallId = "call_" + stateKey + "_" + System.currentTimeMillis();

                                        // 发送 tool_call 事件
                                        Map<String, Object> toolCallEvent = new HashMap<>();
                                        toolCallEvent.put("event", "tool_call");
                                        toolCallEvent.put("toolCallId", toolCallId);
                                        toolCallEvent.put("toolName", "executeCommand");
                                        toolCallEvent.put("status", "success");
                                        emitter.send(objectMapper.writeValueAsString(toolCallEvent) + "\n");

                                        // 发送 tool_result 事件（SSH 命令输出）
                                        Map<String, Object> toolResultEvent = new HashMap<>();
                                        toolResultEvent.put("event", "tool_result");
                                        toolResultEvent.put("toolCallId", toolCallId);
                                        toolResultEvent.put("content", resultContent);
                                        toolResultEvent.put("status", "success");
                                        emitter.send(objectMapper.writeValueAsString(toolResultEvent) + "\n");

                                        log.info("工具执行结果已发送: stateKey={}, resultLength={}", stateKey, resultContent.length());
                                    }
                                }
                            }

                        } catch (Exception ex) {
                            log.warn("SSE 发送异常: {}", ex.getMessage());
                        }
                    });

                    // 发送 done 事件
                    Map<String, Object> doneEvent = new HashMap<>();
                    doneEvent.put("event", "done");
                    doneEvent.put("content", textAccumulator.toString());
                    emitter.send(objectMapper.writeValueAsString(doneEvent) + "\n");

                    emitter.complete();
                    log.info("MVP 流式对话完成 sessionId={}", finalSessionId);

                } catch (Exception e) {
                    log.error("MVP 流式对话异常", e);
                    try {
                        Map<String, Object> errorEvent = new HashMap<>();
                        errorEvent.put("event", "error");
                        errorEvent.put("content", e.getMessage());
                        emitter.send(new ObjectMapper().writeValueAsString(errorEvent) + "\n");
                    } catch (Exception ignored) {
                    }
                    emitter.completeWithError(e);
                } finally {
                    streaming.set(false);
                    heartbeatThread.interrupt();
                    // 清理 ThreadLocal 和会话级变量
                    SshExecuteAdkTool.clearCurrentTerminalSession();
                }
            }, "mvp-stream-" + sessionId).start();

            return emitter;

        } catch (Exception e) {
            log.error("MVP 流式对话初始化失败", e);
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            emitter.completeWithError(e);
            return emitter;
        }
    }
}