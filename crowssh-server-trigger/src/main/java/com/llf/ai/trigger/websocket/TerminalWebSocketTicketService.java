package com.llf.ai.trigger.websocket;

import com.llf.ai.domain.ssh.service.ISshTerminalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 签发并消费终端 WebSocket 一次性短期票据。
 */
@Component
public class TerminalWebSocketTicketService {

    static final long TICKET_TTL_MILLIS = 30_000L;

    private static final Logger DIAGNOSTIC =
            LoggerFactory.getLogger("CROWSSH_TERMINAL_DIAGNOSTIC");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ISshTerminalService terminalService;
    private final Clock clock;
    private final Map<String, TicketGrant> tickets = new ConcurrentHashMap<>();

    @Autowired
    public TerminalWebSocketTicketService(ISshTerminalService terminalService) {
        this(terminalService, Clock.systemUTC());
    }

    TerminalWebSocketTicketService(ISshTerminalService terminalService, Clock clock) {
        this.terminalService = terminalService;
        this.clock = clock;
    }

    public IssuedTicket issue(String ownerId, String sessionId, long resumeAfter) {
        if (ownerId == null || ownerId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("设备身份和终端会话不能为空");
        }
        if (resumeAfter < 0) {
            throw new IllegalArgumentException("resumeAfter 不能小于 0");
        }
        if (!terminalService.sessionExists(ownerId, sessionId)) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        evictExpired();
        String token = newToken();
        long expiresAt = clock.millis() + TICKET_TTL_MILLIS;
        tickets.put(token, new TicketGrant(ownerId, sessionId, resumeAfter, expiresAt));
        DIAGNOSTIC.info("event=ticket_issued sessionId={} resumeAfter={} expiresAt={}",
                sessionId, resumeAfter, expiresAt);
        return new IssuedTicket(token, TICKET_TTL_MILLIS / 1000L);
    }

    public TicketGrant consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TicketGrant grant = tickets.remove(token);
        if (grant == null) {
            DIAGNOSTIC.warn("event=ticket_rejected reason=missing_or_consumed");
            return null;
        }
        if (grant.expiresAtMillis() < clock.millis()) {
            DIAGNOSTIC.warn("event=ticket_rejected sessionId={} reason=expired",
                    grant.sessionId());
            return null;
        }
        DIAGNOSTIC.info("event=ticket_consumed sessionId={} resumeAfter={}",
                grant.sessionId(), grant.resumeAfter());
        return grant;
    }

    private void evictExpired() {
        long now = clock.millis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedTicket(String token, long expiresInSeconds) {
    }

    public record TicketGrant(
            String ownerId,
            String sessionId,
            long resumeAfter,
            long expiresAtMillis
    ) {
    }
}
