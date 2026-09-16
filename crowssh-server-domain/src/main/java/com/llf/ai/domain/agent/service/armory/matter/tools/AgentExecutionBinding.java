package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.llf.ai.domain.db.model.valobj.DbResourceBinding;

/** 服务端请求作用域；捕获不可变快照后在实际回调线程安装，禁止继承线程状态。 */
public final class AgentExecutionBinding {
    public sealed interface Resource permits SshBinding, DbBinding {
        String ownerId();
        String agentSessionId();
    }

    public record SshBinding(String ownerId, String terminalSessionId, String connectionId,
                             String agentSessionId) implements Resource { }

    public record DbBinding(DbResourceBinding snapshot) implements Resource {
        public DbBinding {
            if (snapshot == null || snapshot.agentSessionId() == null || snapshot.agentSessionId().isBlank()
                    || snapshot.turnId() == null || snapshot.turnId().isBlank()) {
                throw new IllegalArgumentException("数据库 Agent 必须绑定可信会话和轮次");
            }
        }
        @Override public String ownerId() { return snapshot.ownerId(); }
        @Override public String agentSessionId() { return snapshot.agentSessionId(); }
    }

    private static final ThreadLocal<Resource> CURRENT = new ThreadLocal<>();

    private AgentExecutionBinding() { }

    public static Resource capture() { return CURRENT.get(); }

    public static void set(Resource resource) {
        if (resource == null) CURRENT.remove(); else CURRENT.set(resource);
    }

    public static void clear() { CURRENT.remove(); }

    public static Scope install(Resource resource) {
        Resource previous = capture();
        set(resource);
        return new Scope(previous);
    }

    public static DbResourceBinding requireDb() {
        if (!(capture() instanceof DbBinding binding)) {
            throw new IllegalStateException("当前请求未绑定可信数据库资源");
        }
        return binding.snapshot();
    }

    public static final class Scope implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private final Resource previous;
        private boolean closed;
        private Scope(Resource previous) { this.previous = previous; }
        @Override public void close() {
            if (thread != Thread.currentThread()) throw new IllegalStateException("绑定作用域必须在安装线程关闭");
            if (!closed) { closed = true; set(previous); }
        }
    }
}
