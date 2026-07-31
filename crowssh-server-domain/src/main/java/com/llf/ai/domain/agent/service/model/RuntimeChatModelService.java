package com.llf.ai.domain.agent.service.model;

import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

@Service
public class RuntimeChatModelService {

    private static final Set<String> PROVIDERS = Set.of("openai", "deepseek", "openai-compatible");

    public RuntimeChatModelScope open(RuntimeModelConfig config) {
        RuntimeChatModelContext.set(build(config));
        return new RuntimeChatModelScope();
    }

    public void test(RuntimeModelConfig config) {
        build(config).call("Reply only with OK");
    }

    private ChatModel build(RuntimeModelConfig config) {
        validate(config);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(config.getBaseUrl()))
                .apiKey(config.getApiKey().trim())
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

    private void validate(RuntimeModelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("未提供客户端 AI 配置");
        }

        String provider = required(config.getProvider(), "服务商").toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("不支持的 AI 服务商");
        }

        String apiKey = required(config.getApiKey(), "API Key");
        String model = required(config.getModel(), "模型");
        if (apiKey.length() > 8192 || model.length() > 200) {
            throw new IllegalArgumentException("AI 配置字段长度超出限制");
        }

        Double temperature = config.getTemperature();
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("Temperature 必须在 0 到 2 之间");
        }
        Integer maxTokens = config.getMaxTokens();
        if (maxTokens != null && (maxTokens < 1 || maxTokens > 131072)) {
            throw new IllegalArgumentException("最大输出 Token 必须在 1 到 131072 之间");
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

    private String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
