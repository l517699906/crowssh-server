package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.RunConfig;
import com.google.adk.tools.ToolContext;
import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import java.util.IdentityHashMap;
import java.util.Map;

/** 按服务端本次 RunConfig 对象身份传递绑定，不从模型参数或可变聊天 state 恢复权限。 */
public final class DatabaseInvocationBindings {
    private static final Map<RunConfig, DbResourceBinding> ACTIVE = new IdentityHashMap<>();
    private DatabaseInvocationBindings() { }

    public static AutoCloseable register(RunConfig config, DbResourceBinding binding) {
        synchronized (ACTIVE) {
            if (ACTIVE.containsKey(config)) throw new IllegalStateException("数据库调用已登记");
            ACTIVE.put(config, binding);
        }
        return () -> { synchronized (ACTIVE) { ACTIVE.remove(config, binding); } };
    }

    public static AgentExecutionBinding.DbBinding resolve(ToolContext context) {
        if (context == null) throw new IllegalStateException("数据库工具缺少可信调用上下文");
        DbResourceBinding binding;
        synchronized (ACTIVE) { binding = ACTIVE.get(context.invocationContext().runConfig()); }
        if (binding == null || !binding.ownerId().equals(context.userId())
                || !binding.agentSessionId().equals(context.sessionId())) {
            throw new IllegalStateException("数据库调用已结束或资源身份不匹配");
        }
        return new AgentExecutionBinding.DbBinding(binding);
    }
}
