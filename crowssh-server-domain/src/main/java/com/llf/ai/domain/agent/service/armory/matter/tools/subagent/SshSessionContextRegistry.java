package com.llf.ai.domain.agent.service.armory.matter.tools.subagent;

import com.google.adk.tools.ToolContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务会话与 SSH 终端会话的运行时绑定。
 * <p>
 * ADK 子 Runner 的工具调用可能运行在不同线程，且部分版本不会注入 ToolContext，
 * 因此派发子 Agent 前必须通过显式上下文恢复终端绑定。
 */
public final class SshSessionContextRegistry {

    /**
     * 按业务会话保存 SSH 终端绑定，供子 Agent 在线程切换后恢复执行环境。
     */
    private static final Map<String, String> SESSION_TERMINALS = new ConcurrentHashMap<>();

    /**
     * 最近一次发起流式请求使用的终端，仅作为无法关联业务会话时的最后兜底。
     */
    private static volatile String activeTerminalSessionId;

    private SshSessionContextRegistry() {
    }

    /**
     * 建立或更新业务会话到 SSH 终端的绑定；空 ID 会被忽略以保护映射有效性。
     */
    public static void bind(String sessionId, String terminalSessionId) {
        if (sessionId != null && !sessionId.isBlank() && terminalSessionId != null && !terminalSessionId.isBlank()) {
            SESSION_TERMINALS.put(sessionId, terminalSessionId);
        }
    }

    /**
     * 查询业务会话绑定的 SSH 终端；不存在时返回空 Optional。
     */
    public static Optional<String> get(String sessionId) {
        return Optional.ofNullable(SESSION_TERMINALS.get(sessionId));
    }

    /**
     * 仅当当前进程存在唯一业务会话绑定时返回该终端，用于保守兜底。
     */
    public static Optional<String> only() {
        return SESSION_TERMINALS.size() == 1
                ? Optional.ofNullable(SESSION_TERMINALS.values().iterator().next())
                : Optional.empty();
    }

    /**
     * 记录最近一次流式请求使用的 SSH 终端。
     */
    public static void setActive(String terminalSessionId) {
        activeTerminalSessionId = terminalSessionId;
    }

    /**
     * 查询最近一次流式请求使用的 SSH 终端；不存在时返回空 Optional。
     */
    public static Optional<String> active() {
        return Optional.ofNullable(activeTerminalSessionId);
    }

    /**
     * 从 ToolContext、会话注册表和唯一活跃终端依次解析 SSH 会话。
     * <p>解析顺序体现“显式状态优先、业务绑定次之、全局兜底最后”的隔离原则。</p>
     */
    public static String resolve(ToolContext toolContext) {
        if (toolContext != null) {
            if (toolContext.state().get("terminalSessionId") instanceof String stateValue && !stateValue.isBlank()) {
                return stateValue;
            }
            String boundValue = get(toolContext.sessionId()).orElse(null);
            if (boundValue != null) {
                return boundValue;
            }
        }
        return only().or(SshSessionContextRegistry::active).orElse(null);
    }
}
