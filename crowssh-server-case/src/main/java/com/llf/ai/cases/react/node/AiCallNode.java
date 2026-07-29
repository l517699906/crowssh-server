package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
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
 * <p>核心要点：
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
    private DefaultArmoryFactory defaultArmoryFactory;

    /** tool name 映射：stateDelta key -> tool name */
    private static final Map<String, String> STATE_DELTA_TOOL_MAPPING = Map.of(
            "ssh_result", "executeCommand"
    );

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        String agentId = dynamicContext.getAgentId();

        // 1. 获取 Agent 注册信息和 ADK Runner
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 2. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // 3. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();

        // 4. 绑定终端会话 ID
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
        }

        // 5. 构建用户消息
        Content userContent = Content.builder()
                .role("user")
                .parts(Part.builder().text(lastUserMessage).build())
                .build();

        // 6. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 7. 调用 ADK Runner 并处理事件流
        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        int roundToolCalls = 0;
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            // ADK Runner 会自动执行工具（SpringAI ChatModel.call() 内部执行）
            // 事件流中 event.functionCalls() 为空，但 event.actions().stateDelta() 包含工具结果
            Iterator<Event> events = runner.runAsync(
                    dynamicContext.getUserId(),
                    dynamicContext.getSessionId(),
                    userContent,
                    RunConfig.builder().build()
            ).blockingIterable().iterator();

            int eventCount = 0;
            while (events.hasNext()) {
                Event event = events.next();
                eventCount++;

                // 7.1 处理文本内容（模型的响应文本，包括工具调用后的总结）
                String eventText = event.stringifyContent();
                if (!eventText.isBlank()) {
                    textAccumulator.append(eventText);
                    dynamicContext.setAssistantContent(textAccumulator);
                    sendTextEvent(emitter, eventText, textAccumulator.toString());
                }

                // 7.2 从 stateDelta 检测工具执行结果
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

                            // 发送 SSE 工具调用事件
                            sendToolCallEvent(emitter, toolCallId, toolName, "executing");

                            // 发送 SSE 工具结果事件
                            sendToolResultEvent(emitter, toolCallId, resultContent, "success");

                            roundToolCalls++;
                            dynamicContext.incrementTotalToolCalls();
                        }
                    }
                }

                // 7.3 记录 assistant 内容到消息历史
                if (event.content().isPresent()) {
                    Content content = event.content().get();
                    String role = content.role().orElse("assistant");
                    if ("assistant".equals(role)) {
                        String text = event.stringifyContent();
                        if (!text.isBlank()) {
                            dynamicContext.appendAssistantMessage(text);
                        }
                    }
                }
            }

            log.info("ADK Runner 事件流处理完成，共 {} 个事件", eventCount);

        } catch (Exception e) {
            log.error("ADK Runner 调用失败", e);
            hasError = true;
            errorBuilder.append("ADK Runner error: ").append(e.getMessage());
            dynamicContext.setErrorMessage(errorBuilder.toString());
            dynamicContext.setStopReason("error");
        } finally {
            // 清除终端会话绑定
            if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
                SshExecuteAdkTool.clearCurrentTerminalSession();
            }
        }

        // 8. 更新步数和工具调用统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(
                dynamicContext.getResult().getTotalToolCalls() + roundToolCalls
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length());

        // 9. 发送本轮结束事件
        sendRoundEndEvent(
                dynamicContext.getEmitter(),
                dynamicContext.getStep(),
                dynamicContext.getMaxSteps(),
                !hasError,
                dynamicContext.getResult().getTotalToolCalls()
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
}
