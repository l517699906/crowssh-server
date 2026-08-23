package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.config.ReActProperties;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.cases.react.guard.ToolResultConsistencyGuard;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.llf.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.llf.ai.domain.agent.service.IChatContextService;
import com.llf.ai.domain.agent.service.IChatService;
import com.llf.ai.domain.agent.service.IIntentService;
import com.llf.ai.domain.agent.service.IPromptService;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.agent.service.intent.IntentService;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
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
 * <p>意图识别接入（Phase 3）：本节点是意图子系统与 ReAct 主链路的唯一接入点，
 * 负责三件事——识别、注入、反馈：
 * <pre>
 *   doApply()
 *     ┌─ 识别：intentService.configure(agentApi) + classify(msg)
 *     │        → 结果存入 dynamicContext.currentIntent / currentIntentResult
 *     │        → COMPOUND/UNKNOWN/低置信度 不硬路由，全交主模型
 *     ├─ 注入：buildEnrichedMessageWithDynamicContext()
 *     │        → currentIntent 经 PromptContextVO.intentLabel 进入消息前缀 [用户意图]
 *     └─ 反馈：工具执行后 handleIntentFeedback(toolResult)
 *              → 失败且走偏时 reportFeedback 重分类，更新 currentIntent
 * </pre>
 * 意图标签只做"提示"不做"硬路由"：即便识别为 DIAGNOSE，主模型仍可自主决定调用哪些工具。
 *
 * @author llf
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    private static final String AGENT_EXECUTION_FAILURE_MESSAGE = "智能体执行失败，请稍后重试。";

    @Resource
    private IChatService chatService;

    @Resource
    private IPromptService promptService;

    @Resource
    private IChatContextService chatContextService;

    @Resource
    private ToolResultConsistencyGuard toolResultConsistencyGuard;

    @Resource
    private ReActProperties reactProperties;

    @Resource
    private IIntentService intentService;

    /** 用于取回当前 Agent 的模型配置（openAiApi / chatModelName），供意图识别复用 */
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        // 1. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // 1.1 意图识别：复用当前智能体的模型配置，不单独配置意图识别模型
        //     步骤：①configure 注入 API → ②classify 识别 → ③存入上下文 → ④不硬路由
        recognizeIntent(lastUserMessage, dynamicContext);

        // 2. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();
        dynamicContext.resetRoundToolCalls();

        // 3. 裁剪消息历史（优先级 + 滑动窗口混合策略，token 预算来自配置 crowssh.react.token-budget）
        List<Map<String, Object>> trimmedHistory = trimHistory(dynamicContext);
        dynamicContext.setMessageHistory(new ArrayList<>(trimmedHistory));

        // 4. 显式绑定工具执行上下文。Spring AI 适配层不会向 ADK 工具传递 ToolContext。
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        SshExecuteAdkTool.setCurrentExecutionContext(
                requestParameter.getUserId(),
                terminalSessionId,
                requestParameter.getConnectionId(),
                dynamicContext.getSessionId()
        );

        // 5. 构建动态上下文并注入用户消息
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("注入动态上下文后消息长度: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        // 6. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 7. 通过领域服务调用 ADK Runner，保留会话和 SSH 资源归属校验
        StringBuilder textAccumulator = new StringBuilder();
        boolean hasError = false;

        log.info("调用 ADK Runner，用户消息长度: {}", lastUserMessage.length());

        try {
            Iterator<Event> events = chatService.handleEnrichedMessageStream(
                    dynamicContext.getAgentId(),
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    enrichedMessage,
                    lastUserMessage,
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

                // 7.1 缓冲模型文本，等待本轮工具结果齐备后再做一致性校验和发布。
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                }
            }

            log.info("ADK Runner 事件流处理完成，共 {} 个事件", eventCount);

        } catch (Exception e) {
            if (e instanceof CancellationException || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            log.error("ADK Runner 调用失败: exceptionType={}", e.getClass().getName());
            hasError = true;
            dynamicContext.setErrorMessage(AGENT_EXECUTION_FAILURE_MESSAGE);
            dynamicContext.setStopReason("error");
        } finally {
            // 请求结束后必须清除，避免线程复用时串用其他会话的 SSH 资源。
            SshExecuteAdkTool.clearCurrentTerminalSession();
        }

        int roundToolCalls = processCompletedToolEvents(dynamicContext);
        publishReconciledText(dynamicContext, textAccumulator, hasError);

        // 8. 更新步数和工具调用统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(dynamicContext.getTotalToolCallCount().get());

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length());

        // 9. 发送本轮结束事件
        sendRoundEndEvent(
                dynamicContext,
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getTotalToolCallCount().get()
        );

        // 10. 错误处理
        if (hasError) {
            dynamicContext.setStopReason("error");
        }

        // 11. 路由
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
     * 按配置的 token 预算裁剪消息历史。抽为包级 seam 以便单测直接驱动。
     */
    List<Map<String, Object>> trimHistory(DefaultReActFactory.DynamicContext dynamicContext) {
        return chatContextService.trimHistory(
                dynamicContext.getMessageHistory(), reactProperties.getTokenBudget());
    }

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

    private void publishReconciledText(
            DefaultReActFactory.DynamicContext dynamicContext,
            StringBuilder textAccumulator,
            boolean hasError
    ) throws Exception {
        String originalText = textAccumulator.toString();
        String resolvedText = hasError
                ? originalText
                : toolResultConsistencyGuard.reconcile(
                        originalText, dynamicContext.getCurrentToolResults());

        if (!resolvedText.equals(originalText)) {
            log.warn("模型文本与工具执行事实冲突，已使用服务端工具结果兜底: toolResultCount={}",
                    dynamicContext.getCurrentToolResults().size());
        }

        textAccumulator.setLength(0);
        textAccumulator.append(resolvedText);
        dynamicContext.setAssistantContent(new StringBuilder(resolvedText));
        if (!resolvedText.isBlank()) {
            dynamicContext.appendAssistantMessage(resolvedText);
            sendTextEvent(dynamicContext, resolvedText, resolvedText);
        }
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
            dynamicContext.getExecutedToolCalls().add(toolCallInfo);

            Map<String, Object> toolResultInfo = new LinkedHashMap<>();
            toolResultInfo.put("id", event.getToolCallId());
            toolResultInfo.put("name", event.getToolName());
            toolResultInfo.put("content", resultContent);
            toolResultInfo.put("status", event.getStatus());
            dynamicContext.getCurrentToolResults().add(toolResultInfo);
            dynamicContext.getExecutedToolResults().add(toolResultInfo);

            dynamicContext.incrementTotalToolCalls();
            dynamicContext.incrementRoundToolCalls();
            if ("executeCommand".equals(event.getToolName())) {
                dynamicContext.addRecentCommand(event.getCommand());
            }

            promptService.detectAndRecordMilestone(
                    dynamicContext.getSessionId(), "tool", milestoneContent(event));

            // 日志验证点：这里是工具事实进入上下文缓存的唯一 ReAct 写入点，下一轮才会注入摘要。
            log.info("[上下文管理] [工具执行摘要] AiCallNode 写入: sessionId={}, toolName={}, status={}, resultLength={}",
                    dynamicContext.getSessionId(), event.getToolName(), event.getStatus(), resultContent.length());
            chatContextService.pushToolResult(
                    dynamicContext.getSessionId(), event.getToolName(), resultContent);

            // 反馈回路：根据工具结果判定当前意图是否走偏，必要时重分类
            handleIntentFeedback(dynamicContext, resultContent);
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

    /**
     * 意图识别：复用当前 Agent 的模型配置识别用户意图，结果存入 DynamicContext。
     * <p>
     * 意图标签只做"提示"不做"硬路由"：即便识别为 DIAGNOSE，主模型仍可自主决定调用哪些工具。
     * COMPOUND / UNKNOWN / 低置信度一律不干预，全交主模型决策。
     * <p>
     * 流程：
     * <pre>
     *   ①从 armory 取 AiAgentRegisterVO → 拿到 openAiApi / chatModelName
     *   ②intentService.configure(...) 注入模型配置（复用 Agent 自己的模型，不单独配置）
     *   ③intentService.classify(...) 识别
     *   ④结果写入 dynamicContext.currentIntent / currentIntentResult
     * </pre>
     * 识别失败不阻断主链路：捕获异常后仅记录日志，意图留空即可。
     */
    private void recognizeIntent(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        try {
            AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(dynamicContext.getAgentId());
            if (aiAgentRegisterVO != null && aiAgentRegisterVO.getOpenAiApi() != null) {
                intentService.configure(aiAgentRegisterVO.getOpenAiApi(), aiAgentRegisterVO.getChatModelName());
            }

            IntentResultVO intentResult = intentService.classify(
                    dynamicContext.getSessionId(), dynamicContext.getUserId(), userMessage);
            if (intentResult == null) {
                return;
            }

            log.info("识别到用户意图: {}, 置信度: {}, 候选: {}, 重分类: {}",
                    intentResult.getIntent().getLabel(),
                    intentResult.getConfidence(),
                    intentResult.getCandidateIntents(),
                    intentResult.isReclassified());

            // 保存到上下文，供 Prompt 注入与反馈回路使用
            dynamicContext.setCurrentIntent(intentResult.getIntent().name());
            dynamicContext.setCurrentIntentResult(intentResult);

            // COMPOUND / UNKNOWN / 低置信度：交给主模型自行判断，不再硬路由
            if (intentResult.getIntent() == IntentTypeEnumVO.COMPOUND) {
                log.info("复合意图，候选 {} —— 交由主模型拆解", intentResult.getCandidateIntents());
            } else if (intentResult.getIntent() == IntentTypeEnumVO.UNKNOWN
                    || intentResult.getConfidence() < 0.5) {
                log.info("意图不确定 ({}，conf={}) —— 全交主模型决策",
                        intentResult.getIntent(), intentResult.getConfidence());
            }
        } catch (Exception e) {
            // 意图识别属旁路能力，失败不影响主链路
            log.warn("意图识别失败，跳过意图注入: sessionId={}, exceptionType={}",
                    dynamicContext.getSessionId(), e.getClass().getName());
        }
    }

    /**
     * 反馈回路：根据工具执行结果判定当前意图是否需要重分类。
     * <p>
     * 仅在本轮已有意图识别结果时触发；reportFeedback 返回非 null 表示已重分类，
     * 此时更新 DynamicContext 的当前意图，使后续轮次的 Prompt 注入使用新意图。
     * <p>
     * 流程：
     * <pre>
     *   工具执行完(result)
     *     ├─ 无本轮意图结果 → 直接返回
     *     ├─ 判定 success（非空 && 不像意图走偏）
     *     └─ intentService.reportFeedback(...)
     *          ├─ 返回 null  → 维持原意图
     *          └─ 返回新结果 → 更新 currentIntent / currentIntentResult
     * </pre>
     * 案例：意图 CONFIGURE，工具结果 "No such file" → success=false →
     *       reportFeedback 用候选 MONITOR 递补 → 后续 Prompt 注入 [用户意图] 监控查看。
     */
    private void handleIntentFeedback(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        IntentResultVO lastIntent = dynamicContext.getCurrentIntentResult();
        if (lastIntent == null) {
            return;
        }
        try {
            // 复用 IntentService 的失败特征判定，保持两处逻辑一致
            boolean success = toolResult != null && !toolResult.isBlank()
                    && !IntentService.looksLikeIntentMismatch(toolResult);

            IntentResultVO reclassified = intentService.reportFeedback(
                    dynamicContext.getSessionId(), lastIntent, success, toolResult);
            if (reclassified != null) {
                log.info("反馈回路触发重分类: {} -> {} (conf={})",
                        lastIntent.getIntent(), reclassified.getIntent(), reclassified.getConfidence());
                dynamicContext.setCurrentIntent(reclassified.getIntent().name());
                dynamicContext.setCurrentIntentResult(reclassified);
            }
        } catch (Exception e) {
            log.warn("意图反馈回路执行失败: sessionId={}, exceptionType={}",
                    dynamicContext.getSessionId(), e.getClass().getName());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: 动态上下文注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 构建注入了动态上下文的用户消息
     * 委托 IPromptService 完成环境采集、里程碑获取、前缀构建
     * <p>
     * 意图注入路径：dynamicContext.currentIntent → buildEnrichedMessage(intentLabel)
     * → PromptContextVO.intentLabel → DynamicPromptBuilder 输出 "[用户意图] xxx" 前缀，
     * 让主模型感知当前意图但不强制路由。
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        // 记录用户消息的里程碑
        promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "user", userMessage);

        // 委托领域服务构建富化消息，末位传入意图标签
        return promptService.buildEnrichedMessage(
                userMessage,
                dynamicContext.getUserId(),
                dynamicContext.getSessionId(),
                dynamicContext.getTerminalSessionId(),
                dynamicContext.getRecentCommands(),
                dynamicContext.getMessageHistory(),
                dynamicContext.getCurrentIntent()
        );
    }
}
