package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * ReAct 循环决策节点
 *
 * <p>职责：
 * 1. 检查终止条件（错误、最大步数、用户停止）
 * 2. 决定是否继续循环（回到 AiCallNode）或路由到 UserFeedbackNode
 *
 * <p>终止条件（参考 WaLiCode streamingAgent.ts）：
 * - AI 返回 finish 终止指令
 * - 达到最大步数 (maxSteps)
 * - 达到最大工具调用次数 (maxToolCalls)
 * - 发生错误
 * - 用户主动停止 (user_stop)
 *
 * <p>循环条件：
 * - 上一轮有工具调用（继续对话）
 * - AI 未返回终止指令
 *
 * @author llf
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    /** 墙钟判定时钟；可注入以便测试。 */
    private Clock clock = Clock.systemUTC();

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct LoopDecisionNode - 循环决策，当前步数: {}/{}",
                dynamicContext.getStep(), dynamicContext.getMaxSteps());

        String stopReason = resolveStopReason(dynamicContext);

        // resolveStopReason 返回 null 表示继续 ReAct 循环
        if (stopReason == null) {
            log.info("上一轮有工具调用，继续 ReAct 循环");
            // 清空当前轮次缓冲，准备下一轮
            dynamicContext.resetRoundBuffers();
            return router(requestParameter, dynamicContext);
        }

        // 幂等：仅在尚未设置时写入，保留上游已设的终止原因
        if (dynamicContext.getStopReason() == null) {
            dynamicContext.setStopReason(stopReason);
        }
        if ("max_steps".equals(stopReason)) {
            dynamicContext.getResult().setMaxStepsReached(true);
        }
        log.info("终止 ReAct 循环，stopReason={}", stopReason);
        return router(requestParameter, dynamicContext);
    }

    /**
     * 解析本轮终止原因；返回 {@code null} 表示应继续循环。
     *
     * <p>墙钟超时检查置于最前——即便还有工具调用待继续，超时也优先终止，作为模型卡住时的兜底。
     * 抽为包级纯查询方法（无副作用）以便单测直接驱动。
     */
    String resolveStopReason(DefaultReActFactory.DynamicContext dynamicContext) {
        // 0. 墙钟超时兜底（置于最前，优先于步数/工具调用等其他上限）
        long timeout = dynamicContext.getWallClockTimeoutMillis();
        if (timeout > 0
                && clock.millis() - dynamicContext.getLoopStartEpochMilli() >= timeout) {
            return "idle_timeout";
        }

        // 1. 上游已设终止原因
        String existing = dynamicContext.getStopReason();
        if (existing != null) {
            return existing;
        }

        // 2. 最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            return "max_steps";
        }

        // 3. 最大工具调用次数
        if (dynamicContext.getResult().getTotalToolCalls() >= dynamicContext.getMaxToolCalls()) {
            return "max_tool_calls";
        }

        // 4. assistant 消息包含终止指令
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";
        if (containsFinishCommand(assistantContent)) {
            return "finish";
        }

        // 5. 错误
        if (dynamicContext.getErrorMessage() != null) {
            return "error";
        }

        // 6. 上一轮有工具调用 → 继续循环
        List<Map<String, Object>> currentToolCalls = dynamicContext.getCurrentToolCalls();
        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
            return null;
        }

        // 7. 无工具调用且无终止指令 → 循环完成
        return "completed";
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        String stopReason = dynamicContext.getStopReason();

        // 有终止原因 → 路由到 UserFeedbackNode
        if (stopReason != null) {
            switch (stopReason) {
                case "user_stop":
                    dynamicContext.getResult().setUserStopped(true);
                    break;
                case "idle_timeout":
                    dynamicContext.getResult().setIdleTimeout(true);
                    break;
                case "error":
                    break;
                case "max_steps":
                    dynamicContext.getResult().setMaxStepsReached(true);
                    break;
                case "max_tool_calls":
                    break;
                case "completed":
                case "finish":
                default:
                    break;
            }
            return getBean("reactUserFeedbackNode");
        }

        // 无终止原因 → 继续 ReAct 循环，回到 AiCallNode
        log.info("继续 ReAct 循环，路由到 AiCallNode");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 检查是否包含终止指令
     * 参考 WaLiCode streamingAgent.ts 的终止条件判断
     */
    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) return false;

        String lower = content.toLowerCase();

        // DSL 风格: finish(message=...)
        if (lower.contains("finish(") || lower.contains("finish (")) {
            return true;
        }

        // JSON 风格: {"action": "finish"}
        if (lower.contains("\"action\"") && lower.contains("\"finish\"")) {
            return true;
        }

        // 标签风格: <answer>finish(...)</answer>
        if (lower.contains("<answer>") && lower.contains("finish")) {
            return true;
        }

        return false;
    }
}
