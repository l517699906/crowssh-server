package com.llf.ai.config;

import com.llf.ai.domain.agent.service.armory.matter.mcp.server.DbInspectionMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import com.llf.ai.domain.agent.service.armory.matter.tools.DatabaseCompatibleToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DbAgentToolConfig {
    @Bean("dbInspectionToolCallbackProvider")
    public ToolCallbackProvider dbInspectionToolCallbackProvider(DbInspectionMcpService service) {
        return () -> DatabaseCompatibleToolCallback.inspections(service);
    }
}
