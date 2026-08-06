package com.llf.ai.cases.react.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 防止模型最终文本否认服务端已经记录的工具执行事实。
 */
@Component
public class ToolResultConsistencyGuard {

    static final int MAX_RESPONSE_LENGTH = 12_000;
    private static final String TRUNCATION_MARKER = "\n\n[输出已截断]";
    private static final List<String> TOOL_UNAVAILABLE_PHRASES = List.of(
            "没有可用的终端执行工具",
            "没有可用的终端工具",
            "没有可用的工具",
            "没有终端执行工具",
            "没有终端工具",
            "工具未开放",
            "工具不可用",
            "no terminal tool",
            "no tools available",
            "do not have access to a terminal",
            "don't have access to a terminal",
            "cannot access the terminal",
            "can't access the terminal"
    );
    private static final List<String> COMMAND_UNAVAILABLE_PHRASES = List.of(
            "没法直接跑命令",
            "无法直接跑命令",
            "不能直接跑命令",
            "没法直接执行命令",
            "无法直接执行命令",
            "不能直接执行命令",
            "cannot run commands",
            "can't run commands",
            "unable to run commands",
            "cannot execute commands",
            "can't execute commands"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String reconcile(String modelText, List<Map<String, Object>> toolResults) {
        String original = modelText == null ? "" : modelText;
        List<ResultView> results = resultViews(toolResults);
        if (results.isEmpty() || (!original.isBlank() && !contradictsToolFacts(original, results))) {
            return original;
        }

        StringBuilder fallback = new StringBuilder();
        if (original.isBlank()) {
            fallback.append("模型未生成可用的工具结果摘要，以下为服务端记录的实际执行结果：");
        } else {
            fallback.append("检测到上游模型的最终回复与已完成的工具执行事实冲突，以下为服务端记录的实际执行结果：");
        }

        for (ResultView result : deduplicate(results)) {
            fallback.append("\n\n工具 `")
                    .append(sanitizeToolName(result.name()))
                    .append("`：")
                    .append(statusLabel(result.status()))
                    .append("\n\n实际输出：\n")
                    .append(indent(sanitizeOutput(displayContent(result.content()))));
        }

        return truncate(fallback.toString());
    }

    private boolean contradictsToolFacts(String modelText, List<ResultView> results) {
        String normalized = modelText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, TOOL_UNAVAILABLE_PHRASES)) {
            return true;
        }
        boolean successfulCommand = results.stream().anyMatch(result ->
                "executecommand".equals(result.name().toLowerCase(Locale.ROOT))
                        && "success".equalsIgnoreCase(result.status()));
        return successfulCommand && containsAny(normalized, COMMAND_UNAVAILABLE_PHRASES);
    }

    private boolean containsAny(String text, List<String> phrases) {
        return phrases.stream().anyMatch(text::contains);
    }

    private List<ResultView> resultViews(List<Map<String, Object>> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        List<ResultView> results = new ArrayList<>();
        for (Map<String, Object> result : toolResults) {
            if (result == null) {
                continue;
            }
            String name = stringValue(result.get("name"), "unknown");
            String status = stringValue(result.get("status"), "unknown");
            String content = stringValue(result.get("content"), "");
            results.add(new ResultView(name, status, content));
        }
        return results;
    }

    private List<ResultView> deduplicate(List<ResultView> results) {
        Map<String, ResultView> unique = new LinkedHashMap<>();
        for (ResultView result : results) {
            String display = displayContent(result.content());
            String key = result.status().toLowerCase(Locale.ROOT) + '\n' + display;
            ResultView existing = unique.get(key);
            if (existing == null || (isExecuteCommand(existing.name()) && !isExecuteCommand(result.name()))) {
                unique.put(key, result);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean isExecuteCommand(String name) {
        return "executecommand".equals(name.toLowerCase(Locale.ROOT));
    }

    private String displayContent(String content) {
        if (content == null || content.isBlank()) {
            return "(工具未返回可显示的输出)";
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root != null && root.isObject()) {
                for (String field : List.of("output", "content", "result", "message", "error", "suggestion")) {
                    JsonNode value = root.get(field);
                    if (value == null || value.isNull()) {
                        continue;
                    }
                    if (value.isTextual()) {
                        return value.asText();
                    }
                    return objectMapper.writeValueAsString(value);
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 工具结果按原始文本展示。
        }
        return content;
    }

    private String sanitizeToolName(String name) {
        return name.replace('`', '_')
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', '_');
    }

    private String sanitizeOutput(String output) {
        StringBuilder sanitized = new StringBuilder(output.length());
        output.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || !Character.isISOControl(codePoint)) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }

    private String indent(String value) {
        return "    " + value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\n    ");
    }

    private String truncate(String value) {
        if (value.length() <= MAX_RESPONSE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_RESPONSE_LENGTH - TRUNCATION_MARKER.length())
                + TRUNCATION_MARKER;
    }

    private String statusLabel(String status) {
        if ("success".equalsIgnoreCase(status)) {
            return "成功";
        }
        if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            return "失败";
        }
        return "状态未知（" + status + "）";
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private record ResultView(String name, String status, String content) {
    }
}
