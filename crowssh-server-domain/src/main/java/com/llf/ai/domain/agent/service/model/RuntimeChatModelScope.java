package com.llf.ai.domain.agent.service.model;

public final class RuntimeChatModelScope implements AutoCloseable {

    private boolean closed;
    private final Runnable cleanup;

    RuntimeChatModelScope() {
        this(RuntimeChatModelContext::clear);
    }

    RuntimeChatModelScope(Runnable cleanup) { this.cleanup = cleanup; }

    /** 捕获不可变配置；子线程创建自己的模型实例，不共享父线程的模型缓存。 */
    public static java.util.function.Supplier<RuntimeChatModelScope> capture() {
        var config = RuntimeChatModelContext.config();
        return () -> RuntimeChatModelContext.install(config);
    }

    @Override
    public void close() {
        if (!closed) {
            cleanup.run();
            closed = true;
        }
    }
}
