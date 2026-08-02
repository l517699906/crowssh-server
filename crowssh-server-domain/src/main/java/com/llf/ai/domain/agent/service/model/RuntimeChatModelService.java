package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

@Service
public class RuntimeChatModelService {

    private static final Set<String> PROVIDERS = Set.of("openai", "deepseek", "openai-compatible");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_MODEL_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_MODEL_COUNT = 500;
    private static final int MAX_MODEL_ID_LENGTH = 200;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RuntimeChatModelService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public RuntimeChatModelScope open(RuntimeModelConfig config) {
        RuntimeChatModelContext.set(build(config));
        return new RuntimeChatModelScope();
    }

    public void test(RuntimeModelConfig config) {
        build(config).call("Reply only with OK");
    }

    public List<String> listModels(RuntimeModelConfig config) {
        ValidatedConnection connection = validateConnection(config);
        URI modelsUri = URI.create(connection.baseUrl() + "/v1/models");
        HttpRequest request = HttpRequest.newBuilder(modelsUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + connection.apiKey())
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalArgumentException(
                            "模型列表查询失败（HTTP " + response.statusCode() + "）"
                    );
                }
                return parseModelIds(readBounded(body));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("模型列表查询已中断");
        } catch (IOException error) {
            throw new IllegalArgumentException("无法连接 AI 服务商的模型列表接口");
        }
    }

    private ChatModel build(RuntimeModelConfig config) {
        ValidatedConnection connection = validateConnection(config);
        validateGeneration(config);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(connection.baseUrl())
                .apiKey(connection.apiKey())
                .completionsPath("/v1/chat/completions")
                .embeddingsPath("/v1/embeddings")
                .build();

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(config.getModel().trim())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.2);
        if (config.getMaxTokens() != null) {
            options.maxTokens(config.getMaxTokens());
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options.build())
                .build();
    }

    private ValidatedConnection validateConnection(RuntimeModelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("未提供客户端 AI 配置");
        }

        String provider = required(config.getProvider(), "服务商").toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("不支持的 AI 服务商");
        }

        String apiKey = required(config.getApiKey(), "API Key");
        if (apiKey.length() > 8192) {
            throw new IllegalArgumentException("API Key 长度超出限制");
        }

        URI uri = parseHttpsUri(config.getBaseUrl());
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("openai".equals(provider) && !"api.openai.com".equals(host)) {
            throw new IllegalArgumentException("OpenAI 服务商地址必须为 api.openai.com");
        }
        if ("deepseek".equals(provider) && !"api.deepseek.com".equals(host)) {
            throw new IllegalArgumentException("DeepSeek 服务商地址必须为 api.deepseek.com");
        }
        validatePublicHost(host);
        return new ValidatedConnection(normalizeBaseUrl(config.getBaseUrl()), apiKey);
    }

    private void validateGeneration(RuntimeModelConfig config) {
        String model = required(config.getModel(), "模型");
        if (model.length() > MAX_MODEL_ID_LENGTH) {
            throw new IllegalArgumentException("模型名称长度超出限制");
        }

        Double temperature = config.getTemperature();
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Temperature 必须在 0 到 2 之间");
        }
        Integer maxTokens = config.getMaxTokens();
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 131072)) {
            throw new IllegalArgumentException("最大输出 Token 必须在 1 到 131072 之间");
        }
    }

    private URI parseHttpsUri(String baseUrl) {
        String value = required(baseUrl, "服务地址");
        if (value.length() > 2048) {
            throw new IllegalArgumentException("服务地址长度超出限制");
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("服务地址必须是有效的 HTTPS 地址");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("服务地址必须是有效的 HTTPS 地址");
        }
    }

    private void validatePublicHost(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                boolean carrierGradeNat = bytes.length == 4
                        && (bytes[0] & 0xff) == 100
                        && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127);
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()
                        || uniqueLocalIpv6
                        || carrierGradeNat) {
                    throw new IllegalArgumentException("服务地址不能指向本机或内网地址");
                }
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("无法解析 AI 服务地址");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl.trim();
        value = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return value.toLowerCase(Locale.ROOT).endsWith("/v1")
                ? value.substring(0, value.length() - 3)
                : value;
    }

    private byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_MODEL_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_MODEL_RESPONSE_BYTES) {
            throw new IllegalArgumentException("模型列表响应过大");
        }
        return bytes;
    }

    List<String> parseModelIds(byte[] payload) {
        try {
            JsonNode data = objectMapper.readTree(payload).path("data");
            if (!data.isArray()) {
                throw new IllegalArgumentException("模型列表响应格式无效");
            }

            Set<String> models = new TreeSet<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isEmpty() && id.length() <= MAX_MODEL_ID_LENGTH) {
                    models.add(id);
                    if (models.size() >= MAX_MODEL_COUNT) {
                        break;
                    }
                }
            }
            if (models.isEmpty()) {
                throw new IllegalArgumentException("服务商未返回可用模型");
            }
            return List.copyOf(models);
        } catch (IOException error) {
            throw new IllegalArgumentException("模型列表响应格式无效");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private record ValidatedConnection(String baseUrl, String apiKey) {
    }
}
