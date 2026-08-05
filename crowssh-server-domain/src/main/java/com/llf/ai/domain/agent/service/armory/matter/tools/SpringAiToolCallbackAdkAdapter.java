package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 将 Spring AI ToolCallback 暴露为 ADK BaseTool。
 */
@Slf4j
public final class SpringAiToolCallbackAdkAdapter extends BaseTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolCallback toolCallback;
    private final FunctionDeclaration declaration;

    public SpringAiToolCallbackAdkAdapter(ToolCallback toolCallback) {
        super(toolName(toolCallback), toolDescription(toolCallback));
        this.toolCallback = toolCallback;
        this.declaration = buildDeclaration(toolCallback.getToolDefinition());
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> invokeWithEvents(args, toolContext));
    }

    private Map<String, Object> invokeWithEvents(
            Map<String, Object> args,
            ToolContext toolContext
    ) {
        Map<String, Object> safeArgs = args == null ? Map.of() : new LinkedHashMap<>(args);
        String toolCallId = "call_" + UUID.randomUUID();
        String agentSessionId = SshExecuteAdkTool.currentAgentSessionId();
        long startedAt = System.currentTimeMillis();
        ToolExecutionObserverRegistry.publish(
                agentSessionId,
                ToolExecutionEvent.running(toolCallId, name(), safeArgs, startedAt)
        );

        Map<String, Object> result;
        String status;
        String errorMessage = null;
        int outputLength;
        try {
            String output = invoke(safeArgs, toolContext);
            outputLength = output == null ? 0 : output.length();
            result = parseOutput(output);
            status = isSuccessful(result) ? "success" : "error";
            if ("error".equals(status)) {
                errorMessage = resultMessage(result);
            }
        } catch (Exception error) {
            log.error("Spring AI 工具执行失败: tool={}", name(), error);
            errorMessage = error.getMessage() == null ? "工具执行失败" : error.getMessage();
            result = Map.of("status", "error", "message", errorMessage);
            status = "error";
            outputLength = 0;
        }

        ToolExecutionObserverRegistry.publish(
                agentSessionId,
                ToolExecutionEvent.completed(
                        toolCallId,
                        name(),
                        safeArgs,
                        result,
                        status,
                        startedAt,
                        System.currentTimeMillis(),
                        outputLength,
                        errorMessage
                )
        );
        return result;
    }

    private String invoke(Map<String, Object> args, ToolContext toolContext) throws Exception {
        String input = OBJECT_MAPPER.writeValueAsString(args == null ? Map.of() : args);
        if (toolContext == null || toolContext.state().isEmpty()) {
            return toolCallback.call(input);
        }
        org.springframework.ai.chat.model.ToolContext springToolContext =
                new org.springframework.ai.chat.model.ToolContext(
                        new LinkedHashMap<>(toolContext.state()));
        return toolCallback.call(input, springToolContext);
    }

    private Map<String, Object> parseOutput(String output) {
        if (output == null || output.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(output, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of("result", output);
        }
    }

    private boolean isSuccessful(Map<String, Object> result) {
        Object success = result.get("success");
        if (success instanceof Boolean) {
            return (Boolean) success;
        }
        Object status = result.get("status");
        return status == null
                || !("error".equalsIgnoreCase(status.toString())
                || "failed".equalsIgnoreCase(status.toString()));
    }

    private String resultMessage(Map<String, Object> result) {
        for (String key : new String[]{"message", "error", "suggestion", "output"}) {
            Object value = result.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "工具返回失败状态";
    }

    private static FunctionDeclaration buildDeclaration(ToolDefinition definition) {
        FunctionDeclaration.Builder builder = FunctionDeclaration.builder()
                .name(definition.name())
                .description(definition.description());
        String inputSchema = definition.inputSchema();
        if (inputSchema != null && !inputSchema.isBlank()) {
            try {
                builder.parametersJsonSchema(OBJECT_MAPPER.readValue(inputSchema, Object.class));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Spring AI 工具 JSON Schema 无效: " + definition.name(), e);
            }
        }
        return builder.build();
    }

    private static String toolName(ToolCallback toolCallback) {
        if (toolCallback == null || toolCallback.getToolDefinition() == null) {
            throw new IllegalArgumentException("Spring AI ToolCallback 及其定义不能为空");
        }
        String name = toolCallback.getToolDefinition().name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Spring AI 工具名称不能为空");
        }
        return name;
    }

    private static String toolDescription(ToolCallback toolCallback) {
        String description = toolCallback.getToolDefinition().description();
        return description == null ? "" : description;
    }
}
