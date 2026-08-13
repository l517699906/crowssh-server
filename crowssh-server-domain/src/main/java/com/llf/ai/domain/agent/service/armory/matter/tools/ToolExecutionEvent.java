package com.llf.ai.domain.agent.service.armory.matter.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具边界产生的结构化执行事件。
 */
public final class ToolExecutionEvent {

    private final String toolCallId;
    private final String toolName;
    private final Map<String, Object> arguments;
    private final Map<String, Object> result;
    private final String status;
    private final long startedAt;
    private final long completedAt;
    private final long durationMs;
    private final int outputLength;
    private final String errorMessage;
    private final String approvalId;
    private final String riskLevel;

    private ToolExecutionEvent(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> result,
            String status,
            long startedAt,
            long completedAt,
            int outputLength,
            String errorMessage,
            String approvalId,
            String riskLevel
    ) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.arguments = immutableCopy(arguments);
        this.result = immutableCopy(result);
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = completedAt > 0 ? Math.max(0, completedAt - startedAt) : 0;
        this.outputLength = outputLength;
        this.errorMessage = errorMessage;
        this.approvalId = approvalId;
        this.riskLevel = riskLevel;
    }

    public static ToolExecutionEvent running(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            long startedAt
    ) {
        return new ToolExecutionEvent(
                toolCallId, toolName, arguments, Map.of(), "running", startedAt, 0, 0, null,
                null, null);
    }

    public static ToolExecutionEvent approvalRequired(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            long startedAt,
            String approvalId,
            String riskLevel
    ) {
        return new ToolExecutionEvent(
                toolCallId, toolName, arguments, Map.of(), "approval_required", startedAt, 0, 0,
                null, approvalId, riskLevel);
    }

    public static ToolExecutionEvent completed(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> result,
            String status,
            long startedAt,
            long completedAt,
            int outputLength,
            String errorMessage
    ) {
        return new ToolExecutionEvent(
                toolCallId, toolName, arguments, result, status, startedAt, completedAt,
                outputLength, errorMessage, null, null);
    }

    public boolean isCompleted() {
        return completedAt > 0;
    }

    public String getCommand() {
        Object command = arguments.get("command");
        return command == null ? null : command.toString();
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public String getStatus() {
        return status;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public int getOutputLength() {
        return outputLength;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
