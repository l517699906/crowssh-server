package com.llf.ai.domain.agent.service.event;

import com.google.adk.events.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Agent 事件路由器。
 * <p>按业务 session 隔离，支持子 Agent 在线程池中发布事件；监听器异常被隔离。</p>
 */
@Slf4j
@Component
public class AgentEventPublisher implements IAgentEventPublisher {
    private final ConcurrentHashMap<String, Set<EventListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void register(String sessionId, EventListener listener) {
        if (hasText(sessionId) && listener != null) {
            listeners.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(listener);
        }
    }

    @Override
    public void unregister(String sessionId, EventListener listener) {
        if (!hasText(sessionId) || listener == null) return;
        Set<EventListener> current = listeners.get(sessionId);
        if (current != null) {
            current.remove(listener);
            if (current.isEmpty()) listeners.remove(sessionId, current);
        }
    }

    @Override
    public void publish(String invocationId, String sessionId, Event event, boolean nested) {
        if (hasText(sessionId)) dispatch(sessionId, event, nested);
    }

    @Override
    public void publishToOnlyActiveSession(Event event, boolean nested) {
        if (listeners.size() != 1) return;
        listeners.values().iterator().next().forEach(listener -> notifySafely(listener, event, nested));
    }

    private void dispatch(String sessionId, Event event, boolean nested) {
        Set<EventListener> current = listeners.get(sessionId);
        if (current != null) current.forEach(listener -> notifySafely(listener, event, nested));
    }

    private void notifySafely(EventListener listener, Event event, boolean nested) {
        try {
            listener.onEvent(event, nested);
        } catch (Exception exception) {
            log.warn("Agent 事件监听器执行失败: {}", exception.getClass().getSimpleName());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value);
    }
}
