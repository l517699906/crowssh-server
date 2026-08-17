package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReAct 用户反馈节点（结果封装 + 清理）
 *
 * <p>职责：
 * 1. 构建最终 ReActResultDTO
 * 2. 清理 ThreadLocal 上下文
 *
 * <p>这是 ReAct 循环链路的终点，负责：
 * - 将累积的响应文本封装为最终结果
 * - 由 case 层统一完成 SSE 结果发送
 * - 清理终端会话绑定
 *
 * @author llf
 */
@Slf4j
@Component("reactUserFeedbackNode")
public class UserFeedbackNode extends AbstractAIAgentReActSupport {

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct UserFeedbackNode - 发送最终结果");

        try {
            ReActResultDTO result = buildFinalResult(dynamicContext);

            log.info("ReAct 完成 - 步数: {}, 工具调用: {}, 停止原因: {}",
                    result.getTotalSteps(),
                    result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode 结果封装失败: exceptionType={}",
                    e.getClass().getName());
            throw e;
        } finally {
            cleanup(dynamicContext);
        }
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // ═══════════════════════════════════════════════════════════════
    //  构建最终结果
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建最终结果 DTO
     */
    private ReActResultDTO buildFinalResult(DefaultReActFactory.DynamicContext dynamicContext) {
        String fullText = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        String stopReason = dynamicContext.getStopReason();
        if (stopReason == null) {
            stopReason = "completed";
        }

        return ReActResultDTO.builder()
                .content(fullText)
                .totalSteps(dynamicContext.getStep())
                .totalToolCalls(dynamicContext.getTotalToolCallCount().get())
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(dynamicContext.getExecutedToolCalls())
                .toolResults(dynamicContext.getExecutedToolResults())
                .error(dynamicContext.getErrorMessage())
                .build();
    }

    /**
     * 清理上下文资源
     */
    private void cleanup(DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            // 清除终端会话绑定
            String sessionId = dynamicContext.getSessionId();
            if (sessionId != null) {
                unbindTerminalSession(sessionId);
            }

            // 清除 ThreadLocal
            clearCurrentTerminalSession();

            log.debug("ReAct 上下文清理完成 sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("ReAct 上下文清理异常: exceptionType={}", e.getClass().getName());
        }
    }
}
