package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * ReAct 用户反馈节点（结果发送 + 清理）
 *
 * <p>职责：
 * 1. 构建最终 ReActResultDTO
 * 2. 发送 done SSE 事件
 * 3. 关闭 Emitter
 * 4. 清理 ThreadLocal 上下文
 *
 * <p>这是 ReAct 循环链路的终点，负责：
 * - 将累积的响应文本封装为最终结果
 * - 通过 SSE 发送 done 事件通知前端
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

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();

        try {
            // 1. 构建最终结果
            ReActResultDTO result = buildFinalResult(dynamicContext);

            // 2. 发送 done SSE 事件
            sendDoneEvent(emitter, result);

            // 3. 关闭 emitter
            emitter.complete();

            log.info("ReAct 完成 - 步数: {}, 工具调用: {}, 停止原因: {}",
                    result.getTotalSteps(),
                    result.getTotalToolCalls(),
                    result.getStopReason() != null ? result.getStopReason() : "completed");

            return result;

        } catch (Exception e) {
            log.error("ReAct UserFeedbackNode 发送失败", e);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
            throw e;
        } finally {
            // 4. 清理上下文
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
                .totalToolCalls(dynamicContext.getResult() != null ? dynamicContext.getResult().getTotalToolCalls() : 0)
                .maxStepsReached("max_steps".equals(stopReason))
                .userStopped("user_stop".equals(stopReason))
                .idleTimeout("idle_timeout".equals(stopReason))
                .stopReason(stopReason)
                .toolCalls(dynamicContext.getCurrentToolCalls())
                .toolResults(dynamicContext.getCurrentToolResults())
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
            log.warn("ReAct 上下文清理异常: {}", e.getMessage());
        }
    }
}
