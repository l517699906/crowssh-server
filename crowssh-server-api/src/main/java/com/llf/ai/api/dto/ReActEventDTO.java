package com.llf.ai.api.dto;

import lombok.Data;

/**
 * ReAct 对话事件 DTO
 *
 * <p>对应 CrowCode 的 streamingAgent.ts 的回调事件结构
 *
 * @author llf
 */
@Data
public class ReActEventDTO {

    /** 事件协议版本 */
    private int schemaVersion;

    /** 事件唯一 ID */
    private String eventId;

    /** 会话内递增序号 */
    private long sequence;

    /** 事件时间戳 */
    private long timestamp;

    /** AI 对话会话 ID */
    private String sessionId;

    /**
     * 事件类型
     * - text: 文本片断
     * - tool_approval_required: 工具调用等待用户审批
     * - tool_call: 工具调用开始
     * - tool_result: 工具执行结果
     * - round_end: 一轮结束
     * - done: 全部完成
     * - error: 错误
     */
    private String event;

    /**
     * 事件内容（文本、片断 ID 等）
     */
    private String content;

    /**
     * 工具调用 ID（tool_call / tool_result 时）
     */
    private String toolCallId;

    /**
     * 工具名称（tool_call 时）
     */
    private String toolName;

    /** SSH 命令（工具执行事件时） */
    private String command;

    /**
     * 工具调用状态（tool_call / tool_result 时）
     * - approval_required: 等待用户审批
     * - running: 执行中
     * - success: 执行成功
     * - error: 执行失败
     * - denied / expired / cancelled: 未执行终态
     */
    private String status;

    /**
     * 完整文本（累积，event=text 时）
     */
    private String fullText;

    /** 工具开始时间 */
    private Long startedAt;

    /** 工具完成时间 */
    private Long completedAt;

    /** 工具执行耗时 */
    private Long durationMs;

    /** 工具输出长度 */
    private Integer outputLength;

    /** 工具执行错误 */
    private String errorMessage;

    /** 一次性命令审批 ID */
    private String approvalId;

    /** 审批过期时间戳 */
    private Long expiresAt;

    /** 命令风险级别 */
    private String riskLevel;

    /** ReAct 终止原因 */
    private String stopReason;

    /** 错误码 */
    private String code;

    /** 错误是否可重试 */
    private Boolean retryable;

    /**
     * 步数信息（round_end 时）
     */
    private StepInfo stepInfo;

    @Data
    public static class StepInfo {
        /** 当前步数 */
        private int currentStep;
        /** 最大步数 */
        private int maxSteps;
        /** 是否继续执行 */
        private boolean shouldContinue;
        /** 工具调用总数 */
        private int totalToolCalls;
    }

}
