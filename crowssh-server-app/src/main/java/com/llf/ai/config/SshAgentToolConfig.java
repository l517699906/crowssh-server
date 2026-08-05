package com.llf.ai.config;

import com.llf.ai.domain.agent.service.armory.matter.mcp.server.SshInspectionMcpService;
import com.llf.ai.domain.agent.service.armory.matter.mcp.server.SshRemoteFileMcpService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSH Agent 的本地只读工具提供者。
 */
@Configuration
public class SshAgentToolConfig {

    @Bean("sshInspectionToolCallbackProvider")
    public ToolCallbackProvider sshInspectionToolCallbackProvider(
            SshInspectionMcpService inspectionService) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(inspectionService)
                .build();
    }

    @Bean("sshRemoteFileToolCallbackProvider")
    public ToolCallbackProvider sshRemoteFileToolCallbackProvider(
            SshRemoteFileMcpService remoteFileService) {
        return MethodToolCallbackProvider
                .builder()
                .toolObjects(remoteFileService)
                .build();
    }
}
