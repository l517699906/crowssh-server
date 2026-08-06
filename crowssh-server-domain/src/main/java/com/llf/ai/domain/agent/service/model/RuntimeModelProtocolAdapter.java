package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

interface RuntimeModelProtocolAdapter {

    /** ADK 1.2.0 不会把 functionResponse 转为 Spring AI ToolResponseMessage。 */
    boolean INTERNAL_TOOL_EXECUTION_ENABLED = true;

    String protocol();

    ChatModel build(RuntimeModelConnection connection,
                    RuntimeModelConfig config,
                    List<ToolCallback> toolCallbacks);

    HttpRequest modelListRequest(RuntimeModelConnection connection, Duration timeout);

    List<String> parseModelIds(byte[] payload, ObjectMapper objectMapper);
}
