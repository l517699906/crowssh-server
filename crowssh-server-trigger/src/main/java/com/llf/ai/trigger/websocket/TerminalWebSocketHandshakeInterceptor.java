package com.llf.ai.trigger.websocket;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Arrays;
import java.util.Map;

/**
 * 从 WebSocket 子协议头中消费一次性票据，避免票据出现在 URL 和访问日志中。
 */
@Component
public class TerminalWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PROTOCOL = "crowssh-terminal";
    public static final String GRANT_ATTRIBUTE = "terminalTicketGrant";

    private final TerminalWebSocketTicketService ticketService;

    public TerminalWebSocketHandshakeInterceptor(TerminalWebSocketTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String header = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
        String[] protocols = header == null
                ? new String[0]
                : Arrays.stream(header.split(",")).map(String::trim).toArray(String[]::new);
        if (protocols.length != 2 || !PROTOCOL.equals(protocols[0])) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        TerminalWebSocketTicketService.TicketGrant grant = ticketService.consume(protocols[1]);
        if (grant == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(GRANT_ATTRIBUTE, grant);
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 无需额外处理。
    }
}
