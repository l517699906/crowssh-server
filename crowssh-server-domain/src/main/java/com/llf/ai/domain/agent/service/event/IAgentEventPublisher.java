package com.llf.ai.domain.agent.service.event;

import com.google.adk.events.Event;

/**
 * 子 Agent/工具事件发布端口。实现负责会话隔离，监听器异常不得影响 Agent 主流程。
 */
public interface IAgentEventPublisher {
    void register(String sessionId, EventListener listener);

    void unregister(String sessionId, EventListener listener);

    void publish(String invocationId, String sessionId, Event event, boolean nested);

    void publishToOnlyActiveSession(Event event, boolean nested);

    @FunctionalInterface
    interface EventListener {
        void onEvent(Event event, boolean nested);
    }
}
