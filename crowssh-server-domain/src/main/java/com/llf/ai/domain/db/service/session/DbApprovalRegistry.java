package com.llf.ai.domain.db.service.session;

import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** DB 审批只保存哈希和资源快照；消费必须位于执行启动的同一协调边界。 */
public final class DbApprovalRegistry {
    public enum State { PENDING, APPROVED, CONSUMED, DENIED, CANCELLED, EXPIRED, INVALIDATED }
    public record Ticket(String id, DbResourceBinding binding, String executionId,
                         String toolCallId, String sqlHash, Instant expiresAt) { }
    private static final class Entry {
        final Ticket ticket;
        State state = State.PENDING;
        Entry(Ticket ticket) { this.ticket = ticket; }
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private final Clock clock;
    private final int ownerLimit;
    private final int globalLimit;

    public DbApprovalRegistry(Clock clock, int ownerLimit, int globalLimit) {
        this.clock = Objects.requireNonNull(clock);
        if (ownerLimit < 1 || globalLimit < 1) throw new IllegalArgumentException("审批配额必须为正数");
        this.ownerLimit = ownerLimit;
        this.globalLimit = globalLimit;
    }

    public synchronized Ticket request(DbResourceBinding binding, String executionId,
                                       String toolCallId, String sqlHash, long ttlSeconds) {
        Objects.requireNonNull(binding);
        requireText(binding.agentSessionId()); requireText(binding.turnId());
        requireText(executionId); requireText(toolCallId);
        if (sqlHash == null || !sqlHash.matches("[a-f0-9]{64}") || ttlSeconds < 1) {
            throw new IllegalArgumentException("审批哈希或期限不合法");
        }
        // 终态随执行完成显式释放；总记录数也有硬上限，不能因大量拒绝而无界增长。
        if (entries.size() >= globalLimit || entries.values().stream()
                .filter(e -> e.ticket.binding().ownerId().equals(binding.ownerId())).count() >= ownerLimit) {
            throw new IllegalStateException("数据库审批配额已用尽");
        }
        Ticket ticket = new Ticket("dbapproval_" + UUID.randomUUID(), binding, executionId,
                toolCallId, sqlHash, clock.instant().plusSeconds(ttlSeconds));
        entries.put(ticket.id(), new Entry(ticket));
        return ticket;
    }

    public synchronized State decide(String id, String owner, String agentSession, boolean approved) {
        Entry entry = owned(id, owner, agentSession);
        expire(entry);
        if (entry.state == State.PENDING) entry.state = approved ? State.APPROVED : State.DENIED;
        notifyAll();
        return entry.state;
    }

    public synchronized State decide(String id, String owner, String agentSession, String turnId, boolean approved) {
        Entry entry = owned(id, owner, agentSession);
        if (!Objects.equals(entry.ticket.binding().turnId(), turnId)) throw new IllegalArgumentException("数据库审批不存在");
        return decide(id, owner, agentSession, approved);
    }

    public synchronized State await(Ticket ticket) throws InterruptedException {
        Entry entry = exact(ticket);
        while (entry.state == State.PENDING) {
            expire(entry);
            if (entry.state != State.PENDING) break;
            long millis = java.time.Duration.between(clock.instant(), ticket.expiresAt()).toMillis();
            try { wait(Math.max(1, Math.min(millis, 1000))); }
            catch (InterruptedException interrupted) {
                if (entry.state == State.PENDING || entry.state == State.APPROVED) entry.state = State.CANCELLED;
                notifyAll();
                throw interrupted;
            }
        }
        return entry.state;
    }

    public synchronized boolean consume(Ticket ticket, DbResourceBinding current, String executionId, String sqlHash) {
        Entry entry = exact(ticket);
        expire(entry);
        if (entry.state != State.APPROVED) return false;
        if (!ticket.binding().equals(current) || !ticket.executionId().equals(executionId)
                || !ticket.sqlHash().equals(sqlHash)) {
            entry.state = State.INVALIDATED;
            notifyAll();
            return false;
        }
        entry.state = State.CONSUMED;
        return true;
    }

    public synchronized State cancel(Ticket ticket) {
        Entry entry = exact(ticket);
        expire(entry);
        if (entry.state == State.PENDING || entry.state == State.APPROVED) entry.state = State.CANCELLED;
        notifyAll();
        return entry.state;
    }

    public synchronized int cancelTurn(String ownerId, String agentSessionId, String turnId) {
        requireText(ownerId); requireText(agentSessionId); requireText(turnId);
        return transitionMatching(binding -> binding.ownerId().equals(ownerId)
                && binding.agentSessionId().equals(agentSessionId) && binding.turnId().equals(turnId), State.CANCELLED);
    }

    public synchronized int invalidateSession(String ownerId, String dbSessionId, long generation) {
        requireText(ownerId); requireText(dbSessionId);
        return transitionMatching(binding -> binding.ownerId().equals(ownerId)
                && binding.dbSessionId().equals(dbSessionId) && binding.sessionGeneration() == generation, State.INVALIDATED);
    }

    private int transitionMatching(java.util.function.Predicate<DbResourceBinding> predicate, State target) {
        int count = 0;
        for (Entry entry : entries.values()) {
            expire(entry);
            if ((entry.state == State.PENDING || entry.state == State.APPROVED) && predicate.test(entry.ticket.binding())) {
                entry.state = target;
                count++;
            }
        }
        notifyAll();
        return count;
    }

    public synchronized void complete(Ticket ticket) {
        Entry entry = exact(ticket);
        if (entry.state == State.PENDING || entry.state == State.APPROVED) entry.state = State.CANCELLED;
        entries.remove(ticket.id(), entry);
        notifyAll();
    }

    private Entry owned(String id, String owner, String agentSession) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.ticket.binding().ownerId().equals(owner)
                || !entry.ticket.binding().agentSessionId().equals(agentSession)) {
            throw new IllegalArgumentException("数据库审批不存在");
        }
        return entry;
    }
    private Entry exact(Ticket ticket) {
        Entry entry = entries.get(ticket.id());
        if (entry == null || !entry.ticket.equals(ticket)) throw new IllegalArgumentException("数据库审批不存在");
        return entry;
    }
    private void expire(Entry entry) {
        if ((entry.state == State.PENDING || entry.state == State.APPROVED)
                && !clock.instant().isBefore(entry.ticket.expiresAt())) entry.state = State.EXPIRED;
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("审批绑定字段不能为空");
    }
}
