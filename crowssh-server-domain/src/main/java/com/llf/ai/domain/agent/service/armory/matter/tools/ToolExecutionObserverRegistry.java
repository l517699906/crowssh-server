package com.llf.ai.domain.agent.service.armory.matter.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 按 AI 会话隔离工具执行观察器。
 */
@Slf4j
public final class ToolExecutionObserverRegistry {

    private static final Map<String, CopyOnWriteArraySet<Observer>> OBSERVERS =
            new ConcurrentHashMap<>();

    private ToolExecutionObserverRegistry() {
    }

    public static void register(String agentSessionId, Observer observer) {
        if (agentSessionId != null && !agentSessionId.isBlank() && observer != null) {
            OBSERVERS.computeIfAbsent(agentSessionId, ignored -> new CopyOnWriteArraySet<>())
                    .add(observer);
        }
    }

    public static void unregister(String agentSessionId, Observer observer) {
        if (agentSessionId != null && !agentSessionId.isBlank() && observer != null) {
            CopyOnWriteArraySet<Observer> observers = OBSERVERS.get(agentSessionId);
            if (observers != null) {
                observers.remove(observer);
                if (observers.isEmpty()) OBSERVERS.remove(agentSessionId, observers);
            }
        }
    }

    public static boolean hasObserver(String agentSessionId) {
        CopyOnWriteArraySet<Observer> observers = OBSERVERS.get(agentSessionId);
        return observers != null && !observers.isEmpty();
    }

    public static void publish(String agentSessionId, ToolExecutionEvent event) {
        CopyOnWriteArraySet<Observer> observers = agentSessionId == null
                ? null : OBSERVERS.get(agentSessionId);
        if (observers == null || observers.isEmpty()) {
            return;
        }
        for (Observer observer : observers) {
            try {
                observer.onExecutionEvent(event);
            } catch (Exception e) {
                log.warn("工具执行状态通知失败: toolCallId={}, exceptionType={}",
                        event.getToolCallId(), e.getClass().getName());
            }
        }
    }

    @FunctionalInterface
    public interface Observer {
        void onExecutionEvent(ToolExecutionEvent event);
    }
}
