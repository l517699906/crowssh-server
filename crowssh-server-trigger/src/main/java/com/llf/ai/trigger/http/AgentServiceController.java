package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.*;
import com.llf.ai.api.response.Response;
import com.llf.ai.cases.IAIAgentReActServiceCase;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.armory.matter.tools.CommandApprovalService;
import com.llf.ai.types.enums.ResponseCode;
import com.llf.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController {

    private static final String AGENT_INITIALIZATION_FAILURE_MESSAGE = "智能体初始化失败，请稍后重试。";

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase aiAgentReActServiceCase;

    @Resource
    private CommandApprovalService commandApprovalService;

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
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
            log.error("查询智能体配置列表异常: exceptionType={}", e.getClass().getName());
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败: exceptionType={}", e.getClass().getName());
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    public Response<CreateSessionResponseDTO> createSession(
            @RequestBody CreateSessionRequestDTO requestDTO,
            Principal principal
    ) {
        String ownerId = principal.getName();
        try {
            log.info("创建会话 agentId:{} userId:{} connectionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), ownerId, requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId());
            String sessionId = chatService.createSession(
                    requestDTO.getAgentId(),
                    ownerId,
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
            log.error("创建会话异常: exceptionType={}", e.getClass().getName());
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{} exceptionType={}",
                    requestDTO.getAgentId(), ownerId, e.getClass().getName());
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(
            @RequestParam("agentId") String agentId,
            Principal principal
    ) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        return createSession(requestDTO, principal);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO,
                                          Principal principal) {
        String ownerId = principal.getName();
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), ownerId);
            String sessionId = chatService.resolveSession(
                    requestDTO.getAgentId(),
                    ownerId,
                    requestDTO.getSessionId(),
                    requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId()
            );

            List<String> messages = chatService.handleMessage(
                    requestDTO.getAgentId(), ownerId, sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常: exceptionType={}", e.getClass().getName());
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话失败 agentId:{} userId:{} exceptionType={}",
                    requestDTO.getAgentId(), ownerId, e.getClass().getName());
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * AI 流式事件接口。Trigger 层仅负责 HTTP 入参和出参，ReAct 编排由 case 层完成。
     */
    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO,
                                          Principal principal) {
        try {
            requestDTO.setUserId(principal.getName());
            log.info("ReAct 流式对话 agentId:{} userId:{} sessionId:{} connectionId:{} terminalSessionId:{} messageLength:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(),
                    requestDTO.getConnectionId(),
                    requestDTO.getTerminalSessionId(),
                    requestDTO.getMessage() == null ? 0 : requestDTO.getMessage().length());
            return aiAgentReActServiceCase.chatStream(requestDTO);

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败: exceptionType={}", e.getClass().getName());
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            emitter.completeWithError(new IllegalStateException(AGENT_INITIALIZATION_FAILURE_MESSAGE));
            return emitter;
        }
    }

    @PostMapping("command_approvals/{approvalId}/decision")
    public Response<Void> decideCommandApproval(
            @PathVariable String approvalId,
            @RequestBody CommandApprovalDecisionRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            if (requestDTO == null || requestDTO.getDecision() == null) {
                throw new IllegalArgumentException("审批决定不能为空");
            }
            CommandApprovalService.Decision decision = switch (
                    requestDTO.getDecision().trim().toLowerCase()) {
                case "approve", "approved" -> CommandApprovalService.Decision.APPROVED;
                case "deny", "denied" -> CommandApprovalService.Decision.DENIED;
                default -> throw new IllegalArgumentException("审批决定只能是 approve 或 deny");
            };
            CommandApprovalService.Decision actual = commandApprovalService.decide(
                    approvalId,
                    principal.getName(),
                    requestDTO.getSessionId(),
                    decision);
            if (actual != decision) {
                return Response.<Void>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("命令审批已结束: " + actual.name().toLowerCase())
                        .build();
            }
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(decision == CommandApprovalService.Decision.APPROVED
                            ? "命令已批准" : "命令已拒绝")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }

    @PostMapping("chat_stream/cancel")
    public Response<Void> cancelChatStream(
            @RequestBody ChatStreamCancelRequestDTO requestDTO,
            Principal principal
    ) {
        try {
            if (requestDTO == null) {
                throw new IllegalArgumentException("取消请求不能为空");
            }
            aiAgentReActServiceCase.cancelStream(
                    principal.getName(),
                    requestDTO.getSessionId(),
                    requestDTO.getTerminalSessionId());
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("取消请求已处理")
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<Void>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        }
    }
}
