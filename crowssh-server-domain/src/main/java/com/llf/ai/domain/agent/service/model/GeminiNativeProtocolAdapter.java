package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;

final class GeminiNativeProtocolAdapter implements RuntimeModelProtocolAdapter {

    static final String PROTOCOL = "gemini-native";

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public ChatModel build(RuntimeModelConnection connection,
                           RuntimeModelConfig config,
                           List<ToolCallback> toolCallbacks) {
        Client client = Client.builder()
                .apiKey(connection.apiKey())
                .httpOptions(HttpOptions.builder()
                        .baseUrl(connection.baseUrl())
                        .apiVersion("v1beta")
                        .build())
                .build();

        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder()
                .model(config.getModel().trim())
                .internalToolExecutionEnabled(INTERNAL_TOOL_EXECUTION_ENABLED);
        if (!Boolean.TRUE.equals(config.getOmitTemperature()) && config.getTemperature() != null) {
            options.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            options.maxOutputTokens(config.getMaxTokens());
        }
        if (!toolCallbacks.isEmpty()) {
            options.toolCallbacks(toolCallbacks);
        }

        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(options.build())
                .build();
    }

    @Override
    public HttpRequest modelListRequest(RuntimeModelConnection connection, Duration timeout) {
        return HttpRequest.newBuilder(connection.endpoint(connection.modelListPath()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("x-goog-api-key", connection.apiKey())
                .GET()
                .build();
    }

    @Override
    public List<String> parseModelIds(byte[] payload, ObjectMapper objectMapper) {
        return RuntimeModelResponseParser.parseGemini(payload, objectMapper);
    }
}
