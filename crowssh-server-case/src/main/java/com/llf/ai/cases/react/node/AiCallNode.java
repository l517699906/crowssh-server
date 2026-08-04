package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.IPromptService;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.CancellationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * AI 调用节点（ReAct 循环核心）
 *
 * <p>职责：
 * 1. 调用 ADK runner.runAsync() 获取事件流
 * 2. 处理文本内容，发送 SSE 事件
 * 3. 从 event.actions().stateDelta() 检测工具执行结果
 * 4. 如果有工具调用：存储到上下文，发送 SSE 事件，路由到 ToolCallNode
 * 5. 如果无工具调用：路由到 LoopDecisionNode
 *
 * <p>核心修复：
 * SpringAI 的 ChatModel.call() 自动执行工具，导致 event.functionCalls() 永远为空。
 * 修复方案：从 event.actions().stateDelta() 检测工具执行结果。
 * stateDelta 包含工具输出（key = output-key, value = 执行结果）。
 *
 * <p>ReAct 循环流程：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用 ADK runner，解析事件）
 *         ├→ [stateDelta 有结果] ToolCallNode → AiCallNode（循环）
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

    /** tool name 映射：stateDelta key -> tool name */
    private static final Map<String, String> STATE_DELTA_TOOL_MAPPING = Map.of(
            "ssh_result", "executeCommand"
    );

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

                // 6.2 从 stateDelta 检测工具执行结果
                EventActions actions = event.actions();
                if (actions != null) {
                    Map<String, Object> stateDelta = actions.stateDelta();
                    if (stateDelta != null && !stateDelta.isEmpty()) {
                        log.info("检测到 stateDelta 变更: keys={}", stateDelta.keySet());

                        for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
                            String stateKey = entry.getKey();
                            Object stateValue = entry.getValue();

                            // 跳过内部状态键（如 "REMOVED"）
                            if ("REMOVED".equals(stateValue)) {
                                continue;
                            }

                            String toolName = resolveToolName(stateKey);
                            String resultContent = formatStateValue(stateValue);
                            String toolCallId = "call_" + stateKey + "_" + System.currentTimeMillis();

                            log.info("工具执行结果: stateKey={}, toolName={}, result_length={}",
                                    stateKey, toolName, resultContent.length());

                            // 存储工具调用信息
                            Map<String, Object> toolCallInfo = new HashMap<>();
                            toolCallInfo.put("id", toolCallId);
                            toolCallInfo.put("name", toolName);
                            toolCallInfo.put("args", "");
                            dynamicContext.getCurrentToolCalls().add(toolCallInfo);

                            // 存储工具结果
                            Map<String, Object> toolResultInfo = new HashMap<>();
                            toolResultInfo.put("id", toolCallId);
                            toolResultInfo.put("name", toolName);
                            toolResultInfo.put("content", resultContent);
                            toolResultInfo.put("status", "success");
                            dynamicContext.getCurrentToolResults().add(toolResultInfo);

                            // SSH 工具由 ExecutionObserver 发送含命令与耗时的精确事件，避免重复。
                            if (!"executeCommand".equals(toolName)) {
                                sendToolCallEvent(dynamicContext, toolCallId, toolName, "executing");
                                sendToolResultEvent(dynamicContext, toolCallId, resultContent, "success");
                            }

                            roundToolCalls++;
                            dynamicContext.incrementTotalToolCalls();

                            // 记录执行的命令到上下文
                            if ("executeCommand".equals(toolName) && !resultContent.isEmpty()) {
                                recordExecutedCommand(dynamicContext, resultContent);
                            }

                            // 记录里程碑（工具结果）
                            promptService.detectAndRecordMilestone(
                                    dynamicContext.getSessionId(), "tool", resultContent);
                        }
                    }
                }

                // 6.3 记录 assistant 内容到消息历史
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

        // 检查本轮是否有工具调用（从 stateDelta 检测到的）
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

    /**
     * 从 stateDelta key 解析工具名称
     */
    private String resolveToolName(String stateKey) {
        // 已知映射
        String mapped = STATE_DELTA_TOOL_MAPPING.get(stateKey);
        if (mapped != null) {
            return mapped;
        }

        // 从 key 推断：去掉 _result 后缀
        if (stateKey.endsWith("_result")) {
            return stateKey.substring(0, stateKey.length() - 7);
        }

        return stateKey;
    }

    /**
     * 格式化 stateDelta 值为字符串
     */
    private String formatStateValue(Object value) {
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

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: 动态上下文注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从工具结果中提取命令并记录到最近命令列表
     */
    private void recordExecutedCommand(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        if (toolResult.length() > 1000) {
            dynamicContext.addRecentCommand(truncate(toolResult, 80) + "...");
        } else {
            dynamicContext.addRecentCommand(toolResult);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

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
