package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.DisposableBean;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RuntimeChatModelService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeChatModelService.class);
    static final String CAPABILITY_PROBE_TOOL_NAME = "crowsshCapabilityProbe";
    private static final String CAPABILITY_PROBE_PROMPT = """
            This is an end-to-end tool-result round-trip test. Call crowsshCapabilityProbe exactly once
            with {"value":"ok"}. After the tool returns, reply with exactly the value returned by the tool.
            Do not call another tool, do not invent the returned value, and do not claim tools are unavailable.
            """;
    private static final ToolDefinition CAPABILITY_PROBE_DEFINITION = ToolDefinition.builder()
            .name(CAPABILITY_PROBE_TOOL_NAME)
            .description("Checks whether the selected model and protocol preserve a complete tool-result round trip")
            .inputSchema("""
                    {"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}
                    """)
            .build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_MODEL_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_MODEL_ID_LENGTH = 200;
    private static final Set<String> PROTOCOLS = Set.of(
            OpenAiChatProtocolAdapter.PROTOCOL,
            AnthropicMessagesProtocolAdapter.PROTOCOL,
            GeminiNativeProtocolAdapter.PROTOCOL
    );
    private static final Set<String> AUTH_TYPES = Set.of("bearer", "x-api-key", "api-key", "custom");
    private static final Set<String> TOKEN_PARAMETERS = Set.of(
            "auto",
            "max_tokens",
            "max_completion_tokens"
    );
    private static final Set<String> FORBIDDEN_AUTH_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "upgrade",
            "proxy-authorization",
            "proxy-authenticate",
            "forwarded",
            "via",
            "te",
            "trailer",
            "cookie",
            "set-cookie"
    );
    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$");
    private static final Map<String, ProviderPolicy> PROVIDER_POLICIES = Map.ofEntries(
            Map.entry("openai", new ProviderPolicy(
                    OpenAiChatProtocolAdapter.PROTOCOL,
                    Set.of("api.openai.com"),
                    "bearer",
                    "models"
            )),
            Map.entry("anthropic", new ProviderPolicy(
                    AnthropicMessagesProtocolAdapter.PROTOCOL,
                    Set.of("api.anthropic.com"),
                    "x-api-key",
                    "v1/models"
            )),
            Map.entry("gemini", new ProviderPolicy(
                    GeminiNativeProtocolAdapter.PROTOCOL,
                    Set.of("generativelanguage.googleapis.com"),
                    "x-api-key",
                    "v1beta/models"
            )),
            Map.entry("deepseek", new ProviderPolicy(
                    OpenAiChatProtocolAdapter.PROTOCOL,
                    Set.of("api.deepseek.com"),
                    "bearer",
                    "models"
            )),
            Map.entry("openrouter", new ProviderPolicy(
                    OpenAiChatProtocolAdapter.PROTOCOL,
                    Set.of("openrouter.ai"),
                    "bearer",
                    "models"
            )),
            Map.entry("groq", new ProviderPolicy(
                    OpenAiChatProtocolAdapter.PROTOCOL,
                    Set.of("api.groq.com"),
                    "bearer",
                    "models"
            )),
            Map.entry("dashscope", new ProviderPolicy(
                    OpenAiChatProtocolAdapter.PROTOCOL,
                    Set.of("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com"),
                    "bearer",
                    "models"
            ))
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, RuntimeModelProtocolAdapter> adapters;

    public RuntimeChatModelService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
        this.adapters = List.<RuntimeModelProtocolAdapter>of(
                        new OpenAiChatProtocolAdapter(),
                        new AnthropicMessagesProtocolAdapter(),
                        new GeminiNativeProtocolAdapter()
                ).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        RuntimeModelProtocolAdapter::protocol,
                        adapter -> adapter
                ));
    }

    public RuntimeChatModelScope open(RuntimeModelConfig config) {
        validateConnection(config);
        validateGeneration(config);
        RuntimeChatModelContext.set(config);
        return new RuntimeChatModelScope();
    }

    public void test(RuntimeModelConfig config) {
        CapabilityProbe probe = new CapabilityProbe();
        ChatModel model = build(config, List.of(probe));
        try {
            verifyToolCalling(model, probe);
        } finally {
            destroy(model);
        }
    }

    void verifyToolCalling(ChatModel model) {
        verifyToolCalling(model, new CapabilityProbe());
    }

    private void verifyToolCalling(ChatModel model, CapabilityProbe probe) {
        ToolCallingChatOptions probeOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(probe))
                .internalToolExecutionEnabled(true)
                .build();
        ChatResponse response = model.call(new Prompt(CAPABILITY_PROBE_PROMPT, probeOptions));

        if (probe.executionCount() == 0) {
            throw new ToolRoundTripException(
                    "模型连接成功，但未执行工具调用；请确认模型支持 Function Calling，"
                            + "并检查中转站协议是否匹配 OpenAI Chat 或 Anthropic Messages"
            );
        }
        if (probe.executionCount() != 1) {
            throw new ToolRoundTripException("模型重复执行了工具能力探针，中转站工具调用协议不兼容");
        }
        if (!probe.receivedExpectedInput()) {
            throw new ToolRoundTripException("模型调用了工具，但探针参数不正确，中转站工具参数协议不兼容");
        }
        if (!containsText(response, probe.sentinel()) || containsToolCall(response)) {
            throw new ToolRoundTripException(
                    "工具已经执行，但模型未正确消费工具结果；当前模型或中转站不支持完整的工具结果回传"
            );
        }
    }

    private boolean containsText(ChatResponse response, String expected) {
        if (response == null || response.getResults() == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            String text = generation.getOutput().getText();
            if (text != null && text.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsToolCall(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            if (generation != null
                    && generation.getOutput() != null
                    && generation.getOutput().getToolCalls() != null
                    && !generation.getOutput().getToolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private final class CapabilityProbe implements ToolCallback {

        private final AtomicInteger executionCount = new AtomicInteger();
        private final String sentinel = "CROWSSH_TOOL_ROUND_TRIP_"
                + UUID.randomUUID().toString().replace("-", "");
        private volatile boolean receivedExpectedInput = true;

        @Override
        public ToolDefinition getToolDefinition() {
            return CAPABILITY_PROBE_DEFINITION;
        }

        @Override
        public String call(String toolInput) {
            executionCount.incrementAndGet();
            receivedExpectedInput = hasExpectedInput(toolInput);
            return receivedExpectedInput ? sentinel : "CROWSSH_TOOL_PROBE_INVALID_ARGUMENT";
        }

        private boolean hasExpectedInput(String toolInput) {
            try {
                JsonNode input = objectMapper.readTree(toolInput);
                return input != null
                        && input.isObject()
                        && "ok".equals(input.path("value").asText(null));
            } catch (Exception ignored) {
                return false;
            }
        }

        private int executionCount() {
            return executionCount.get();
        }

        private boolean receivedExpectedInput() {
            return receivedExpectedInput;
        }

        private String sentinel() {
            return sentinel;
        }
    }

    public List<String> listModels(RuntimeModelConfig config) {
        RuntimeModelConnection connection = validateConnection(config);
        RuntimeModelProtocolAdapter adapter = adapter(connection.protocol());
        HttpRequest request = adapter.modelListRequest(connection, REQUEST_TIMEOUT);

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
                return adapter.parseModelIds(readBounded(body), objectMapper);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("模型列表查询已中断");
        } catch (IOException error) {
            throw new IllegalArgumentException("无法连接 AI 服务商的模型列表接口");
        }
    }

    ChatModel build(RuntimeModelConfig config, List<ToolCallback> toolCallbacks) {
        RuntimeModelConnection connection = validateConnection(config);
        validateGeneration(config);
        LOGGER.info(
                "构建运行时 AI 模型: provider={}, protocol={}, model={}, host={}, toolCount={}",
                connection.provider(),
                connection.protocol(),
                sanitizeLogValue(config.getModel()),
                connection.baseUri().getHost(),
                toolCallbacks.size()
        );
        return adapter(connection.protocol()).build(connection, config, List.copyOf(toolCallbacks));
    }

    private RuntimeModelProtocolAdapter adapter(String protocol) {
        RuntimeModelProtocolAdapter adapter = adapters.get(protocol);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的 AI 调用协议");
        }
        return adapter;
    }

    private void destroy(ChatModel model) {
        if (!(model instanceof DisposableBean disposable)) {
            return;
        }
        try {
            disposable.destroy();
        } catch (Exception error) {
            LOGGER.debug("关闭测试用 AI 模型失败", error);
        }
    }

    private RuntimeModelConnection validateConnection(RuntimeModelConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("未提供客户端 AI 配置");
        }

        String provider = required(config.getProvider(), "服务商").toLowerCase(Locale.ROOT);
        boolean customProvider = "openai-compatible".equals(provider);
        ProviderPolicy policy = PROVIDER_POLICIES.get(provider);
        if (!customProvider && policy == null) {
            throw new IllegalArgumentException("不支持的 AI 服务商");
        }

        boolean legacyProtocol = config.getProtocol() == null || config.getProtocol().trim().isEmpty();
        String protocol = optional(config.getProtocol(), customProvider
                ? OpenAiChatProtocolAdapter.PROTOCOL
                : policy.protocol()).toLowerCase(Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("不支持的 AI 调用协议");
        }
        if (!customProvider && !policy.protocol().equals(protocol)) {
            throw new IllegalArgumentException("服务商与调用协议不匹配");
        }

        String expectedAuth = customProvider
                ? defaultAuthType(protocol)
                : policy.authType();
        String authType = optional(config.getAuthType(), expectedAuth).toLowerCase(Locale.ROOT);
        if (!AUTH_TYPES.contains(authType)) {
            throw new IllegalArgumentException("不支持的 API Key 鉴权方式");
        }
        if (!OpenAiChatProtocolAdapter.PROTOCOL.equals(protocol) && !"x-api-key".equals(authType)) {
            throw new IllegalArgumentException("原生协议仅支持官方 API Key 鉴权方式");
        }
        if (!customProvider && !policy.authType().equals(authType)) {
            throw new IllegalArgumentException("服务商与鉴权方式不匹配");
        }

        String apiKey = required(config.getApiKey(), "API Key");
        if (apiKey.length() > 8192) {
            throw new IllegalArgumentException("API Key 长度超出限制");
        }

        URI uri = parseHttpsUri(config.getBaseUrl());
        if (legacyProtocol && OpenAiChatProtocolAdapter.PROTOCOL.equals(protocol)) {
            uri = migrateLegacyOpenAiBaseUri(uri);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!customProvider && !policy.hosts().contains(host)) {
            throw new IllegalArgumentException("服务商地址与所选官方服务商不匹配");
        }
        validatePublicHost(host);

        String modelListPath = customProvider
                ? optional(config.getModelListPath(), defaultModelListPath(protocol))
                : policy.modelListPath();
        modelListPath = validateRelativePath(modelListPath, "模型列表路径");

        String authHeader = null;
        String authPrefix = null;
        if ("custom".equals(authType)) {
            authHeader = validateAuthHeader(config.getAuthHeader());
            authPrefix = validateAuthPrefix(config.getAuthPrefix());
        }

        return new RuntimeModelConnection(
                provider,
                protocol,
                normalizeBaseUri(uri),
                apiKey,
                authType,
                authHeader,
                authPrefix,
                modelListPath
        );
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
        String tokenParameter = optional(config.getTokenParameter(), "auto").toLowerCase(Locale.ROOT);
        if (!TOKEN_PARAMETERS.contains(tokenParameter)) {
            throw new IllegalArgumentException("不支持的 Token 参数");
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
                    || uri.getFragment() != null
                    || uri.getRawPath().contains("..")) {
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

    private URI normalizeBaseUri(URI uri) {
        String value = uri.toString();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value);
    }

    private URI migrateLegacyOpenAiBaseUri(URI uri) {
        URI normalized = normalizeBaseUri(uri);
        return normalized.getPath().toLowerCase(Locale.ROOT).endsWith("/v1")
                ? normalized
                : URI.create(normalized + "/v1");
    }

    private String validateRelativePath(String path, String field) {
        String value = required(path, field).replaceFirst("^/+", "");
        if (value.length() > 500
                || value.contains("..")
                || value.contains("?")
                || value.contains("#")
                || value.contains(":")) {
            throw new IllegalArgumentException(field + "必须是有效的相对路径");
        }
        return value;
    }

    private String validateAuthHeader(String header) {
        String value = required(header, "自定义鉴权 Header");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!HEADER_NAME.matcher(value).matches()
                || FORBIDDEN_AUTH_HEADERS.contains(normalized)
                || normalized.startsWith("x-forwarded-")
                || normalized.startsWith("sec-")) {
            throw new IllegalArgumentException("自定义鉴权 Header 名称无效");
        }
        return value;
    }

    private String validateAuthPrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        if (prefix.length() > 100 || prefix.contains("\r") || prefix.contains("\n")) {
            throw new IllegalArgumentException("自定义鉴权前缀无效");
        }
        return prefix;
    }

    private String defaultAuthType(String protocol) {
        return OpenAiChatProtocolAdapter.PROTOCOL.equals(protocol) ? "bearer" : "x-api-key";
    }

    private String defaultModelListPath(String protocol) {
        return switch (protocol) {
            case AnthropicMessagesProtocolAdapter.PROTOCOL -> "v1/models";
            case GeminiNativeProtocolAdapter.PROTOCOL -> "v1beta/models";
            default -> "models";
        };
    }

    private byte[] readBounded(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_MODEL_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_MODEL_RESPONSE_BYTES) {
            throw new IllegalArgumentException("模型列表响应过大");
        }
        return bytes;
    }

    List<String> parseModelIds(byte[] payload) {
        return RuntimeModelResponseParser.parseOpenAi(payload, objectMapper);
    }

    List<String> parseGeminiModelIds(byte[] payload) {
        return RuntimeModelResponseParser.parseGemini(payload, objectMapper);
    }

    private String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }

    private String optional(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String sanitizeLogValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
    }

    public static final class ToolRoundTripException extends IllegalArgumentException {

        public ToolRoundTripException(String message) {
            super(message);
        }
    }

    private record ProviderPolicy(String protocol,
                                  Set<String> hosts,
                                  String authType,
                                  String modelListPath) {
    }
}
