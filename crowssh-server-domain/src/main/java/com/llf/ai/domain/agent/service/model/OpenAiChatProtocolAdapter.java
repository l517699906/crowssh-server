package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class OpenAiChatProtocolAdapter implements RuntimeModelProtocolAdapter {

    static final String PROTOCOL = "openai-chat";

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public ChatModel build(RuntimeModelConnection connection,
                           RuntimeModelConfig config,
                           List<ToolCallback> toolCallbacks) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(connection.origin())
                .apiKey(new NoopApiKey())
                .headers(authHeaders(connection))
                .completionsPath(connection.endpoint("chat/completions").getRawPath())
                .embeddingsPath(connection.endpoint("embeddings").getRawPath())
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(config.getModel().trim());
        if (!Boolean.TRUE.equals(config.getOmitTemperature()) && config.getTemperature() != null) {
            options.temperature(config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            if (usesMaxCompletionTokens(connection, config)) {
                options.maxCompletionTokens(config.getMaxTokens());
            } else {
                options.maxTokens(config.getMaxTokens());
            }
        }
        if (!toolCallbacks.isEmpty()) {
            options.toolCallbacks(toolCallbacks);
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .build();
    }

    @Override
    public HttpRequest modelListRequest(RuntimeModelConnection connection, Duration timeout) {
        AuthHeader auth = authHeader(connection);
        return HttpRequest.newBuilder(connection.endpoint(connection.modelListPath()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header(auth.name(), auth.value())
                .GET()
                .build();
    }

    @Override
    public List<String> parseModelIds(byte[] payload, ObjectMapper objectMapper) {
        return RuntimeModelResponseParser.parseOpenAi(payload, objectMapper);
    }

    private MultiValueMap<String, String> authHeaders(RuntimeModelConnection connection) {
        AuthHeader auth = authHeader(connection);
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add(auth.name(), auth.value());
        return headers;
    }

    private AuthHeader authHeader(RuntimeModelConnection connection) {
        return switch (connection.authType()) {
            case "x-api-key" -> new AuthHeader("x-api-key", connection.apiKey());
            case "api-key" -> new AuthHeader("api-key", connection.apiKey());
            case "custom" -> new AuthHeader(
                    connection.authHeader(),
                    (connection.authPrefix() == null ? "" : connection.authPrefix()) + connection.apiKey()
            );
            default -> new AuthHeader("Authorization", "Bearer " + connection.apiKey());
        };
    }

    private boolean usesMaxCompletionTokens(RuntimeModelConnection connection, RuntimeModelConfig config) {
        String parameter = config.getTokenParameter();
        if ("max_completion_tokens".equals(parameter)) {
            return true;
        }
        if (!"auto".equals(parameter)) {
            return false;
        }
        String model = config.getModel().toLowerCase(Locale.ROOT);
        return "openai".equals(connection.provider())
                && (model.startsWith("gpt-5")
                || model.startsWith("o1")
                || model.startsWith("o3")
                || model.startsWith("o4"));
    }

    private record AuthHeader(String name, String value) {
    }
}
