package com.llf.ai.domain.agent.service.model;

public final class RuntimeChatModelScope implements AutoCloseable {

    private boolean closed;

    RuntimeChatModelScope() {
    }

    @Override
    public void close() {
        if (!closed) {
            RuntimeChatModelContext.clear();
            closed = true;
        }
    }
}
