package com.llf.ai.domain.agent.service.armory.matter.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * AI SSH 命令的一次性审批状态机。
 */
@Service
public class CommandApprovalService {

    private static final Logger AUDIT = LoggerFactory.getLogger("CROWSSH_SECURITY_AUDIT");
    private static final int MAX_PENDING_APPROVALS = 1_000;

    private final long timeoutMillis;
    private final Clock clock;
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    public CommandApprovalService(
            @Value("${crowssh.ai.command-approval.timeout-seconds:60}") long timeoutSeconds
    ) {
        this(timeoutSeconds, Clock.systemUTC());
    }

    CommandApprovalService(long timeoutSeconds, Clock clock) {
        if (timeoutSeconds < 10 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("AI 命令审批超时必须在 10 到 300 秒之间");
        }
        this.timeoutMillis = Math.multiplyExact(timeoutSeconds, 1000L);
        this.clock = clock;
    }

    public ApprovalTicket request(
            String ownerId,
            String agentSessionId,
            String terminalSessionId,
            String connectionId,
            String toolCallId,
            String command
    ) {
        requireText(ownerId, "ownerId");
        requireText(agentSessionId, "agentSessionId");
        requireText(terminalSessionId, "terminalSessionId");
        requireText(connectionId, "connectionId");
        requireText(toolCallId, "toolCallId");
        requireText(command, "command");
        if (approvals.size() >= MAX_PENDING_APPROVALS) {
            throw new IllegalStateException("待审批命令过多，请稍后重试");
        }

        long expiresAt = clock.millis() + timeoutMillis;
        ApprovalTicket ticket;
        PendingApproval pending;
        do {
            ticket = new ApprovalTicket(
                    "approval_" + UUID.randomUUID(),
                    toolCallId,
                    commandHash(command),
                    command.length(),
                    expiresAt,
                    "elevated");
            pending = new PendingApproval(
                    ticket, ownerId, agentSessionId, terminalSessionId, connectionId,
                    new CompletableFuture<>());
        } while (approvals.putIfAbsent(ticket.approvalId(), pending) != null);

        PendingApproval scheduled = pending;
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS)
                .execute(() -> expire(scheduled));
        audit("required", scheduled, Decision.PENDING, null, null, null);
        return ticket;
    }

    public Decision awaitDecision(ApprovalTicket ticket) {
        PendingApproval pending = requirePending(ticket.approvalId());
        try {
            return pending.decision().get();
        } catch (InterruptedException e) {
            pending.decision().complete(Decision.CANCELLED);
            audit("decision", pending, Decision.CANCELLED, null, null, null);
            Thread.currentThread().interrupt();
            return Decision.CANCELLED;
        } catch (ExecutionException e) {
            throw new IllegalStateException("等待命令审批失败", e.getCause());
        }
    }

    public Decision decide(
            String approvalId,
            String ownerId,
            String agentSessionId,
            Decision decision
    ) {
        if (decision != Decision.APPROVED && decision != Decision.DENIED) {
            throw new IllegalArgumentException("审批决定只能是 APPROVED 或 DENIED");
        }
        PendingApproval pending = requirePending(approvalId);
        if (!pending.ownerId().equals(ownerId)
                || !pending.agentSessionId().equals(agentSessionId)) {
            throw new IllegalArgumentException("命令审批不属于当前设备或 AI 会话");
        }
        if (clock.millis() >= pending.ticket().expiresAt()) {
            expire(pending);
        } else if (pending.decision().complete(decision)) {
            audit("decision", pending, decision, null, null, null);
        }
        return pending.decision().getNow(Decision.PENDING);
    }

    public int cancelSession(String ownerId, String agentSessionId) {
        int cancelled = 0;
        for (PendingApproval pending : approvals.values()) {
            if (pending.ownerId().equals(ownerId)
                    && pending.agentSessionId().equals(agentSessionId)
                    && pending.decision().complete(Decision.CANCELLED)) {
                cancelled++;
                audit("decision", pending, Decision.CANCELLED, null, null, null);
            }
        }
        return cancelled;
    }

    public boolean isCancelled(ApprovalTicket ticket) {
        PendingApproval pending = approvals.get(ticket.approvalId());
        return pending != null && pending.decision().getNow(Decision.PENDING) == Decision.CANCELLED;
    }

    public void recordExecutionResult(
            ApprovalTicket ticket,
            String status,
            Integer exitCode,
            Boolean exitCodeKnown,
            int outputLength
    ) {
        PendingApproval pending = approvals.get(ticket.approvalId());
        if (pending != null) {
            audit("result", pending, pending.decision().getNow(Decision.PENDING),
                    status, exitCodeKnown != null && exitCodeKnown ? exitCode : null, outputLength);
        }
    }

    public void complete(ApprovalTicket ticket) {
        approvals.remove(ticket.approvalId());
    }

    public static String commandHash(String command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (command == null ? "" : command).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }

    private void expire(PendingApproval pending) {
        if (pending.decision().complete(Decision.EXPIRED)) {
            audit("decision", pending, Decision.EXPIRED, null, null, null);
        }
    }

    private PendingApproval requirePending(String approvalId) {
        PendingApproval pending = approvalId == null ? null : approvals.get(approvalId);
        if (pending == null) {
            throw new IllegalArgumentException("命令审批不存在或已结束");
        }
        return pending;
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private void audit(
            String event,
            PendingApproval pending,
            Decision decision,
            String resultStatus,
            Integer exitCode,
            Integer outputLength
    ) {
        AUDIT.info(
                "event={} approvalId={} ownerId={} agentSessionId={} terminalSessionId={} connectionId={} toolCallId={} commandHash={} commandLength={} decision={} resultStatus={} exitCode={} outputLength={}",
                event,
                pending.ticket().approvalId(),
                pending.ownerId(),
                pending.agentSessionId(),
                pending.terminalSessionId(),
                pending.connectionId(),
                pending.ticket().toolCallId(),
                pending.ticket().commandHash(),
                pending.ticket().commandLength(),
                decision,
                resultStatus,
                exitCode,
                outputLength);
    }

    public enum Decision {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED,
        CANCELLED
    }

    public record ApprovalTicket(
            String approvalId,
            String toolCallId,
            String commandHash,
            int commandLength,
            long expiresAt,
            String riskLevel
    ) {
    }

    private record PendingApproval(
            ApprovalTicket ticket,
            String ownerId,
            String agentSessionId,
            String terminalSessionId,
            String connectionId,
            CompletableFuture<Decision> decision
    ) {
    }
}
