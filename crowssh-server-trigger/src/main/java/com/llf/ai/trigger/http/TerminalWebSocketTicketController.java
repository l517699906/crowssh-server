package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.TerminalWebSocketTicketRequestDTO;
import com.llf.ai.api.dto.TerminalWebSocketTicketResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.trigger.websocket.TerminalWebSocketTicketService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 终端 WebSocket 握手票据控制器。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ssh/terminal")
public class TerminalWebSocketTicketController {

    private final TerminalWebSocketTicketService ticketService;

    @PostMapping("/ws-ticket")
    public Response<TerminalWebSocketTicketResponseDTO> issueTicket(
            @RequestBody TerminalWebSocketTicketRequestDTO request,
            Principal principal
    ) {
        try {
            long resumeAfter = request.getResumeAfter() == null ? 0L : request.getResumeAfter();
            TerminalWebSocketTicketService.IssuedTicket issued = ticketService.issue(
                    principal.getName(), request.getSessionId(), resumeAfter);
            return Response.<TerminalWebSocketTicketResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(TerminalWebSocketTicketResponseDTO.builder()
                            .ticket(issued.token())
                            .expiresInSeconds(issued.expiresInSeconds())
                            .build())
                    .build();
        } catch (IllegalArgumentException exception) {
            log.warn("签发终端 WebSocket 票据失败: {}", exception.getMessage());
            return Response.<TerminalWebSocketTicketResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(exception.getMessage())
                    .build();
        }
    }
}
