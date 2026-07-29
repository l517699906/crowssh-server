package com.llf.ai.cases.react.factory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 动态上下文
 *
 * <p>参考 DefaultArmoryFactory.DynamicContext，为 ReAct 执行链路提供状态存储：
 * - 会话信息（sessionId, userId, agentId）
 * - 消息历史（messages + toolResults）
 * - ReAct 循环状态（步数、工具调用计数）
 * - SSE 发射器
 * - 工具定义（ToolCallback[]）
 *
 * <p>ReAct 循环数据流：
 * <pre>
 * RootNode         → 初始化上下文，绑定 SSE emitter
 * AiCallNode       → 构建 AI 请求，追加用户消息到 history
 * ToolCallNode     → 解析 tool_calls，执行工具，追加结果到 history
 * LoopDecisionNode → 判断是否继续（max_steps / finish / 无工具调用）
 * UserFeedbackNode → 发送最终结果，完成 SSE
 * </pre>
 *
 * @author llf
 */
public class DefaultReActFactory {

    /**
     * 动态上下文
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        // ══════════════════════════════════════════════════════════
        //  会话基本信息
        // ══════════════════════════════════════════════════════════

        /** 对话会话 ID */
        private String sessionId;

        /** 用户 ID */
        private String userId;

        /** 智能体 ID */
        private String agentId;

        /** SSH 终端会话 ID */
        private String terminalSessionId;

        /** SSE 事件发射器 */
        private ResponseBodyEmitter emitter;

        // ══════════════════════════════════════════════════════════
        //  消息历史
        //  每轮 ReAct 循环结束后，将 toolCalls + toolResults 追加到这里
        // ══════════════════════════════════════════════════════════

        /**
         * 消息历史
         * 格式：{ role: "user"/"assistant"/"tool", content: "...", tool_call_id?: "..." }
         */
        @Builder.Default
        private List<Map<String, Object>> messageHistory = new ArrayList<>();

        /**
         * 当前轮次的工具调用列表
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolCalls = new ArrayList<>();

        /**
         * 当前轮次的工具执行结果列表
         */
        @Builder.Default
        private List<Map<String, Object>> currentToolResults = new ArrayList<>();

        // ══════════════════════════════════════════════════════════
        //  ReAct 循环状态
        // ══════════════════════════════════════════════════════════

        /** 当前步数 */
        @Builder.Default
        private AtomicInteger currentStep = new AtomicInteger(0);

        /** 最大步数 */
        private int maxSteps;

        /** 最大工具调用次数（总计） */
        private int maxToolCalls;

        /** 每轮最大工具调用次数 */
        private int maxToolCallsPerRound;

        /** 总工具调用次数 */
        @Builder.Default
        private AtomicInteger totalToolCallCount = new AtomicInteger(0);

        /** 当前轮工具调用次数 */
        @Builder.Default
        private AtomicInteger roundToolCallCount = new AtomicInteger(0);

        // ══════════════════════════════════════════════════════════
        //  AI 响应缓冲（用于累积流式文本）
        // ══════════════════════════════════════════════════════════

        /** 累积的文本响应 */
        @Builder.Default
        private StringBuilder assistantContent = new StringBuilder();

        /** 累积的 reasoning_content */
        @Builder.Default
        private StringBuilder assistantReasoning = new StringBuilder();

        /** 上一轮次收到的 reasoning_content（需要回传给 API） */
        private String lastReasoningContent;

        // ══════════════════════════════════════════════════════════
        //  中断状态
        // ══════════════════════════════════════════════════════════

        /** 中断原因：user_stop / idle_timeout / max_steps */
        private String stopReason;

        /** 错误消息（如有） */
        private String errorMessage;

        // ══════════════════════════════════════════════════════════
        //  结果对象（供 UserFeedbackNode 使用）
        // ══════════════════════════════════════════════════════════

        /** 最终结果 DTO */
        private com.llf.ai.api.dto.ReActResultDTO result;

        // ══════════════════════════════════════════════════════════
        //  辅助方法
        // ══════════════════════════════════════════════════════════

        public void incrementStep() {
            currentStep.incrementAndGet();
        }

        public int getStep() {
            return currentStep.get();
        }

        public void incrementTotalToolCalls() {
            totalToolCallCount.incrementAndGet();
        }

        public void incrementRoundToolCalls() {
            roundToolCallCount.incrementAndGet();
        }

        public void resetRoundToolCalls() {
            roundToolCallCount.set(0);
        }

        public void resetRoundBuffers() {
            currentToolCalls.clear();
            currentToolResults.clear();
            assistantContent.setLength(0);
            assistantReasoning.setLength(0);
        }

        public void appendMessage(Map<String, Object> message) {
            messageHistory.add(message);
        }

        public void appendUserMessage(String content) {
            appendMessage(Map.of("role", "user", "content", content));
        }

        public void appendAssistantMessage(String content) {
            appendMessage(Map.of("role", "assistant", "content", content));
        }

        public void appendToolMessage(String toolCallId, String content) {
            Map<String, Object> msg = Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
            messageHistory.add(msg);
        }

    }
}
