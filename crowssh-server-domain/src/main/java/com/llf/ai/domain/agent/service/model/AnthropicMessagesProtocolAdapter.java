package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

final class AnthropicMessagesProtocolAdapter implements RuntimeModelProtocolAdapter {

    static final String PROTOCOL = "anthropic-messages";
    static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public ChatModel build(RuntimeModelConnection connection,
                           RuntimeModelConfig config,
                           List<ToolCallback> toolCallbacks) {
        AnthropicApi anthropicApi = AnthropicApi.builder()
                .baseUrl(connection.origin())
                .completionsPath(connection.versionedEndpoint("v1", "v1/messages").getRawPath())
                .apiKey(connection.apiKey())
                .anthropicVersion(ANTHROPIC_VERSION)
                .build();

        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(config.getModel().trim())
                .maxTokens(config.getMaxTokens() == null ? DEFAULT_MAX_TOKENS : config.getMaxTokens())
                .internalToolExecutionEnabled(INTERNAL_TOOL_EXECUTION_ENABLED);
        if (!Boolean.TRUE.equals(config.getOmitTemperature()) && config.getTemperature() != null) {
            options.temperature(config.getTemperature());
        }
        if (!toolCallbacks.isEmpty()) {
            options.toolCallbacks(toolCallbacks);
        }

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options.build())
                .build();
    }

    @Override
    public HttpRequest modelListRequest(RuntimeModelConnection connection, Duration timeout) {
        return HttpRequest.newBuilder(connection.versionedEndpoint("v1", connection.modelListPath()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("x-api-key", connection.apiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .GET()
                .build();
    }

    @Override
    public List<String> parseModelIds(byte[] payload, ObjectMapper objectMapper) {
        return RuntimeModelResponseParser.parseOpenAi(payload, objectMapper);
    }
}
