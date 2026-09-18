package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.llf.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 子任务作用域同时承载资源身份、取消信号和工具失败证据。 */
public final class SubAgentExecutionScope implements AutoCloseable {
    private static final ThreadLocal<SubAgentExecutionScope> CURRENT = new ThreadLocal<>();
    private final AutoCloseable bindingScope;
    private final AtomicBoolean cancelled;
    private boolean toolFailed;

    private SubAgentExecutionScope(AgentExecutionContext context, AtomicBoolean cancelled) {
        if (CURRENT.get() != null) throw new IllegalStateException("禁止递归派发子 Agent");
        this.cancelled = cancelled;
        bindingScope = SshExecuteAdkTool.executionScope(new SshExecuteAdkTool.ExecutionBinding(
                context.getUserId(), context.getTerminalSessionId(), context.getConnectionId(), context.getParentSessionId()));
        CURRENT.set(this);
    }

    public static SubAgentExecutionScope open(AgentExecutionContext context, AtomicBoolean cancelled) {
        return new SubAgentExecutionScope(context, cancelled);
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
        if (scope.toolFailed) throw new IllegalStateException("子任务已有工具失败，禁止继续执行");
        if (scope.cancelled.get()) throw new CancellationException("子任务已取消");
        return scope;
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("任务已取消");
    }

    public void cancelPlan() { cancelled.set(true); }
    public static void checkActiveIfPresent() {
        if (CURRENT.get() != null) require();
    }

    public void markToolFailed() { toolFailed = true; }
    public boolean toolFailed() { return toolFailed; }

    @Override public void close() throws Exception {
        CURRENT.remove();
        bindingScope.close();
    }
}
