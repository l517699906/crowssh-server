package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.IPromptService;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * AI 调用节点（ReAct 循环核心）
 *
 * <p>职责：
 * 1. 调用 ADK runner.runAsync() 获取事件流
 * 2. 处理文本内容，发送 SSE 事件
 * 3. 消费工具边界发布的结构化执行事件
 * 4. 如果有已完成工具调用：存储结果并路由到 ToolCallNode
 * 5. 如果无工具调用：路由到 LoopDecisionNode
 *
 * <p>ADK runner 会在单次调用内完成模型调用、工具执行和最终响应。
 * stateDelta 是 Agent 状态，不作为工具调用或工具结果的事实来源。
 *
 * <p>ReAct 循环流程：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用 ADK runner，解析事件）
 *         ├→ [有结构化工具结果] ToolCallNode → LoopDecisionNode
 *         └→ [无工具调用] LoopDecisionNode → UserFeedbackNode
 * </pre>
 *
 * @author llf
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private IChatService chatService;

    @Resource
    private IPromptService promptService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        // 1. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // 2. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();

        // 3. 显式绑定工具执行上下文。Spring AI 适配层不会向 ADK 工具传递 ToolContext。
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        SshExecuteAdkTool.setCurrentExecutionContext(
                requestParameter.getUserId(),
                terminalSessionId,
                requestParameter.getConnectionId(),
                dynamicContext.getSessionId()
        );

        // 4. 构建动态上下文并注入用户消息
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("注入动态上下文后消息长度: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        // 5. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 6. 通过领域服务调用 ADK Runner，保留会话和 SSH 资源归属校验
        StringBuilder textAccumulator = new StringBuilder();
        int roundToolCalls = 0;
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            Iterator<Event> events = chatService.handleMessageStream(
                    dynamicContext.getAgentId(),
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    enrichedMessage,
                    terminalSessionId,
                    requestParameter.getConnectionId()
            ).blockingIterable().iterator();

            int eventCount = 0;
            while (events.hasNext()) {
                if (Thread.currentThread().isInterrupted()
                        || (dynamicContext.isStreaming() && !dynamicContext.getStreamActive().get())) {
                    throw new CancellationException("流式连接已结束");
                }
                Event event = events.next();
                eventCount++;

                String eventText = extractTextContent(event);
                log.debug("处理第 {} 个事件: final={}, content_len={}",
                        eventCount,
                        event.finalResponse(),
                        eventText.length());

                // 6.1 处理文本内容（模型的响应文本，包括工具调用后的总结）
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                    dynamicContext.setAssistantContent(textAccumulator);
                    sendTextEvent(dynamicContext, eventText, textAccumulator.toString());
                }

                // 6.2 记录 assistant 内容到消息历史
                if (event.content().isPresent()) {
                    Content content = event.content().get();
                    String role = content.role().orElse("assistant");
                    if ("assistant".equals(role)) {
                        if (!eventText.isBlank()) {
                            dynamicContext.appendAssistantMessage(eventText);
                        }
                    }
                }
            }

            log.info("ADK Runner 事件流处理完成，共 {} 个事件", eventCount);

        } catch (Exception e) {
            if (e instanceof CancellationException || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.error("ADK Runner 调用失败", e);
            hasError = true;
            errorBuilder.append("ADK Runner error: ").append(e.getMessage());
            dynamicContext.setErrorMessage(errorBuilder.toString());
            dynamicContext.setStopReason("error");
        } finally {
            // 请求结束后必须清除，避免线程复用时串用其他会话的 SSH 资源。
            SshExecuteAdkTool.clearCurrentTerminalSession();
        }

        roundToolCalls = processCompletedToolEvents(dynamicContext);

        // 9. 更新步数和工具调用统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(
                dynamicContext.getResult().getTotalToolCalls() + roundToolCalls
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length());

        // 10. 发送本轮结束事件
        sendRoundEndEvent(
                dynamicContext,
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getResult().getTotalToolCalls()
        );

        // 11. 错误处理
        if (hasError) {
            dynamicContext.setStopReason("error");
        }

        // 12. 路由
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        // 检查是否应该终止
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("检测到终止条件: {}, 路由到 UserFeedbackNode", stopReason);
            return getBean("reactUserFeedbackNode");
        }

        // 检查是否达到最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数 {}, 路由到 UserFeedbackNode", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            return getBean("reactUserFeedbackNode");
        }

        // 只有真实完成的工具事件才进入 ToolCallNode。
        if (!dynamicContext.getCurrentToolCalls().isEmpty()) {
            log.info("检测到 {} 个工具调用，路由到 ToolCallNode",
                    dynamicContext.getCurrentToolCalls().size());
            return getBean("reactToolCallNode");
        }

        // 无工具调用 → ReAct 循环完成
        log.info("无工具调用，ReAct 循环完成，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取最新用户消息
     */
    private String getLastUserMessage(ChatRequestDTO requestParameter,
                                      DefaultReActFactory.DynamicContext dynamicContext) {
        if (requestParameter.getMessage() != null && !requestParameter.getMessage().isEmpty()) {
            return requestParameter.getMessage();
        }

        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return "";
    }

    private String extractTextContent(Event event) {
        return event.content()
                .flatMap(Content::parts)
                .stream()
                .flatMap(List::stream)
                .map(part -> part.text().orElse(""))
                .reduce("", String::concat);
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private int processCompletedToolEvents(DefaultReActFactory.DynamicContext dynamicContext) {
        Map<String, ToolExecutionEvent> completedEvents = new LinkedHashMap<>();
        for (ToolExecutionEvent event : dynamicContext.getCurrentToolExecutionEvents()) {
            if (event != null && event.isCompleted()) {
                completedEvents.put(event.getToolCallId(), event);
            }
        }

        for (ToolExecutionEvent event : completedEvents.values()) {
            String resultContent = formatValue(event.getResult());

            Map<String, Object> toolCallInfo = new LinkedHashMap<>();
            toolCallInfo.put("id", event.getToolCallId());
            toolCallInfo.put("name", event.getToolName());
            toolCallInfo.put("args", formatValue(event.getArguments()));
            dynamicContext.getCurrentToolCalls().add(toolCallInfo);

            Map<String, Object> toolResultInfo = new LinkedHashMap<>();
            toolResultInfo.put("id", event.getToolCallId());
            toolResultInfo.put("name", event.getToolName());
            toolResultInfo.put("content", resultContent);
            toolResultInfo.put("status", event.getStatus());
            dynamicContext.getCurrentToolResults().add(toolResultInfo);

            dynamicContext.incrementTotalToolCalls();
            if ("executeCommand".equals(event.getToolName())) {
                dynamicContext.addRecentCommand(event.getCommand());
            }

            promptService.detectAndRecordMilestone(
                    dynamicContext.getSessionId(), "tool", milestoneContent(event));
        }
        return completedEvents.size();
    }

    private String milestoneContent(ToolExecutionEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", event.getToolName());
        payload.put("status", event.getStatus());
        payload.put("result", event.getResult());
        if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
            payload.put("errorMessage", event.getErrorMessage());
        }
        return formatValue(payload);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: 动态上下文注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建注入了动态上下文的用户消息
     * 委托 IPromptService 完成环境采集、里程碑获取、前缀构建
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        // 记录用户消息的里程碑
        promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "user", userMessage);

        // 委托领域服务构建富化消息
        return promptService.buildEnrichedMessage(
                userMessage,
                dynamicContext.getUserId(),
                dynamicContext.getSessionId(),
                dynamicContext.getTerminalSessionId(),
                dynamicContext.getRecentCommands()
        );
    }
}
