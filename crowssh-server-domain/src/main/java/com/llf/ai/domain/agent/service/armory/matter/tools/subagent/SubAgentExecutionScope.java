package com.llf.ai.domain.agent.service.armory.matter.tools.subagent;
import com.llf.ai.domain.agent.service.armory.matter.tools.ssh.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.database.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.subagent.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.execution.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.adapter.*;

import com.llf.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.google.adk.tools.ToolContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 子任务作用域同时承载资源身份、取消信号和工具失败证据。 */
public final class SubAgentExecutionScope implements AutoCloseable {
    private static final ThreadLocal<SubAgentExecutionScope> CURRENT = new ThreadLocal<>();
    private static final Map<String, Registration> REGISTRATIONS = new ConcurrentHashMap<>();

    private record Registration(AgentExecutionContext context,
                                AtomicBoolean cancelled,
                                AtomicBoolean toolFailed) { }

    private final AutoCloseable bindingScope;
    private final AtomicBoolean cancelled;
    private final AtomicBoolean toolFailed;

    private SubAgentExecutionScope(AgentExecutionContext context, AtomicBoolean cancelled) {
        this(context, cancelled, new AtomicBoolean());
    }

    private SubAgentExecutionScope(AgentExecutionContext context,
                                   AtomicBoolean cancelled,
                                   AtomicBoolean toolFailed) {
        if (CURRENT.get() != null) throw new IllegalStateException("禁止递归派发子 Agent");
        this.cancelled = cancelled;
        this.toolFailed = toolFailed;
        bindingScope = SshExecuteAdkTool.executionScope(new SshExecuteAdkTool.ExecutionBinding(
                context.getUserId(), context.getTerminalSessionId(), context.getConnectionId(), context.getParentSessionId()));
        CURRENT.set(this);
    }

    public static SubAgentExecutionScope open(AgentExecutionContext context, AtomicBoolean cancelled) {
        return new SubAgentExecutionScope(context, cancelled);
    }

    /** ADK 工具回调可能切换线程，按子会话恢复可信执行上下文。 */
    public static void register(String childSessionId,
                                AgentExecutionContext context,
                                AtomicBoolean cancelled) {
        register(childSessionId, context, cancelled, new AtomicBoolean());
    }

    static void register(String childSessionId,
                         AgentExecutionContext context,
                         AtomicBoolean cancelled,
                         AtomicBoolean toolFailed) {
        if (childSessionId == null || childSessionId.isBlank()) {
            throw new IllegalArgumentException("子 Agent 会话 ID 不能为空");
        }
        REGISTRATIONS.put(childSessionId,
                new Registration(context, cancelled, toolFailed));
    }

    public static void unregister(String childSessionId) {
        if (childSessionId != null && !childSessionId.isBlank()) {
            REGISTRATIONS.remove(childSessionId);
        }
    }

    static SubAgentExecutionScope openFor(ToolContext toolContext) {
        if (CURRENT.get() != null || toolContext == null) return null;
        String sessionId = toolContext.sessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        Registration registration = REGISTRATIONS.get(sessionId);
        if (registration == null) return null;
        return new SubAgentExecutionScope(registration.context(), registration.cancelled(),
                registration.toolFailed());
    }

    static SubAgentExecutionScope current() {
        return CURRENT.get();
    }

    public static AgentExecutionContext capture(String agentId, String parentAgentName) {
        if (CURRENT.get() != null) throw new IllegalStateException("禁止递归派发子 Agent");
        checkInterrupted();
        var binding = SshExecuteAdkTool.requireCurrentExecutionBinding();
        if (binding.agentSessionId() == null || binding.agentSessionId().isBlank())
            throw new IllegalStateException("派发缺少根聊天会话");
        return AgentExecutionContext.builder().agentId(agentId).parentAgentName(parentAgentName)
                .userId(binding.ownerId()).parentSessionId(binding.agentSessionId())
                .connectionId(binding.connectionId()).terminalSessionId(binding.terminalSessionId()).build();
    }

    public static SubAgentExecutionScope require() {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("子 Agent 工具缺少可信任务作用域");
        checkInterrupted();
        if (scope.toolFailed.get()) throw new IllegalStateException("子任务已有工具失败，禁止继续执行");
        if (scope.cancelled.get()) throw new CancellationException("子任务已取消");
        return scope;
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("任务已取消");
    }

    public static void checkActive(AtomicBoolean cancelled) {
        checkInterrupted();
        if (cancelled != null && cancelled.get()) throw new CancellationException("子任务已取消");
    }

    public void cancelPlan() { cancelled.set(true); }
    public static void checkActiveIfPresent() {
        if (CURRENT.get() != null) require();
    }

    public void markToolFailed() { toolFailed.set(true); }
    public boolean toolFailed() { return toolFailed.get(); }
    AtomicBoolean toolFailureSignal() { return toolFailed; }

    @Override public void close() throws Exception {
        CURRENT.remove();
        bindingScope.close();
    }
}
