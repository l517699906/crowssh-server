package com.llf.ai.domain.agent.service.armory.matter.mcp.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class StdioToolMcpCreateService implements ToolMcpCreateService {
    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters stdioConfig = toolMcp.getStdio();

        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.StdioServerParameters.ServerParameters serverParameters = stdioConfig.getServerParameters();

        ServerParameters stdioParams = ServerParameters.builder(serverParameters.getCommand())
                .args(serverParameters.getArgs())
                .env(serverParameters.getEnv())
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(new StdioClientTransport(stdioParams, new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(Duration.ofSeconds(stdioConfig.getRequestTimeout())).build();

        McpSchema.InitializeResult initialize = mcpSyncClient.initialize();

        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpSyncClient)
                .build()
                .getToolCallbacks();
    }
}
