package com.llf.ai.domain.agent.service.armory.matter.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 AI 会话隔离工具执行观察器。
 */
@Slf4j
public final class ToolExecutionObserverRegistry {

    private static final Map<String, Observer> OBSERVERS = new ConcurrentHashMap<>();

    private ToolExecutionObserverRegistry() {
    }

    public static void register(String agentSessionId, Observer observer) {
        if (agentSessionId != null && !agentSessionId.isBlank() && observer != null) {
            OBSERVERS.put(agentSessionId, observer);
        }
    }

    public static void unregister(String agentSessionId, Observer observer) {
        if (agentSessionId != null && !agentSessionId.isBlank() && observer != null) {
            OBSERVERS.remove(agentSessionId, observer);
        }
    }

    public static void publish(String agentSessionId, ToolExecutionEvent event) {
        Observer observer = agentSessionId == null ? null : OBSERVERS.get(agentSessionId);
        if (observer == null) {
            return;
        }
        try {
            observer.onExecutionEvent(event);
        } catch (Exception e) {
            log.warn("工具执行状态通知失败: toolCallId={}, reason={}",
                    event.getToolCallId(), e.getMessage());
        }
    }

    @FunctionalInterface
    public interface Observer {
        void onExecutionEvent(ToolExecutionEvent event);
    }
}
