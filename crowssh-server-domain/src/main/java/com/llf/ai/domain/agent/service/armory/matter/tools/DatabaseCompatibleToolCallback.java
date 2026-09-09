package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.llf.ai.domain.agent.service.armory.matter.mcp.server.DbInspectionMcpService;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Arrays;

/** 仅由本地数据库诊断或只读技能加载器创建，不能凭工具名称获得数据库装配资格。 */
public final class DatabaseCompatibleToolCallback implements ToolCallback {
    private final ToolCallback delegate;

    private DatabaseCompatibleToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    public static ToolCallback[] inspections(DbInspectionMcpService service) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(service).build().getToolCallbacks())
                .map(DatabaseCompatibleToolCallback::new).toArray(ToolCallback[]::new);
    }

    public static ToolCallback skills(String directory) {
        return new DatabaseCompatibleToolCallback(SkillsTool.builder().addSkillsDirectory(directory).build());
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return delegate.call(input); }
    @Override public String call(String input, ToolContext context) { return delegate.call(input, context); }
}
