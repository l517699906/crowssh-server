package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.config.ReActProperties;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.model.entity.ChatMessageEntity;
import com.llf.ai.domain.agent.service.ILongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct Root Node（根节点）
 *
 * <p>职责：
 * 1. 从 ChatRequestDTO 提取会话参数
 * 2. 初始化 DynamicContext
 * 3. 绑定终端会话 ID（ThreadLocal）
 * 4. 路由到 AiCallNode
 *
 * <p>节点链：
 * RootNode → AiCallNode → ToolCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author llf
 */
@Slf4j
@Component("reactRootNode")
public class RootNode extends AbstractAIAgentReActSupport {

    @Resource
    private ReActProperties reactProperties;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /** 墙钟起点时钟；可注入以便测试。 */
    private Clock clock = Clock.systemUTC();

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct RootNode - 初始化上下文");

        // 1. 提取会话参数
        String sessionId = requestParameter.getSessionId();
        String userId = requestParameter.getUserId();
        String agentId = requestParameter.getAgentId();
        String terminalSessionId = requestParameter.getTerminalSessionId();
        // 空消息按空字符串处理，避免在日志、历史和提示构建阶段触发 NPE。
        String message = requestParameter.getMessage() == null ? "" : requestParameter.getMessage();

        // 2. 绑定终端会话（ThreadLocal，支持异步线程继承）
        if (dynamicContext.getDatabaseBinding() != null) {
            terminalSessionId = null;
            clearCurrentTerminalSession();
        } else if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            setCurrentTerminalSession(terminalSessionId);
        } else {
            // 尝试从会话绑定中获取
            String boundTerminal = getTerminalSession(sessionId);
            if (boundTerminal != null) {
                setCurrentTerminalSession(boundTerminal);
                terminalSessionId = boundTerminal;
            }
        }

        // 3. 初始化上下文
        dynamicContext.setSessionId(sessionId);
        dynamicContext.setUserId(userId);
        dynamicContext.setAgentId(agentId);
        dynamicContext.setTerminalSessionId(terminalSessionId);
        java.util.List<java.util.Map<String, Object>> history = new java.util.ArrayList<>();
        java.util.List<ChatMessageEntity> recentMessages = longTermMemoryService == null
                ? java.util.List.of()
                : longTermMemoryService.getRecentMessages(userId, sessionId, 50);
        if (recentMessages == null) {
            recentMessages = java.util.List.of();
        }
        for (ChatMessageEntity messageEntity : recentMessages) {
            if (messageEntity == null || messageEntity.getRole() == null) {
                continue;
            }
            java.util.Map<String, Object> historyMessage = new java.util.LinkedHashMap<>();
            historyMessage.put("role", messageEntity.getRole());
            historyMessage.put("content", messageEntity.getContent() == null ? "" : messageEntity.getContent());
            if ("tool".equals(messageEntity.getRole())) {
                if (messageEntity.getToolCallId() != null) {
                    historyMessage.put("tool_call_id", messageEntity.getToolCallId());
                }
                if (messageEntity.getToolName() != null) {
                    historyMessage.put("name", messageEntity.getToolName());
                }
            }
            history.add(historyMessage);
        }
        dynamicContext.setMessageHistory(history);
        dynamicContext.setCurrentToolCalls(new java.util.ArrayList<>());
        dynamicContext.setCurrentToolResults(new java.util.ArrayList<>());
        dynamicContext.setExecutedToolCalls(new java.util.ArrayList<>());
        dynamicContext.setExecutedToolResults(new java.util.ArrayList<>());
        dynamicContext.setCurrentStep(new AtomicInteger(0));
        dynamicContext.setTotalToolCallCount(new AtomicInteger(0));
        dynamicContext.setRoundToolCallCount(new AtomicInteger(0));
        seedDynamicContext(dynamicContext);

        // 4. 初始化结果 DTO
        ReActResultDTO result = ReActResultDTO.builder()
                .totalSteps(0)
                .totalToolCalls(0)
                .maxStepsReached(false)
                .userStopped(false)
                .idleTimeout(false)
                .build();
        dynamicContext.setResult(result);

        // 5. 追加用户消息到历史
        dynamicContext.appendUserMessage(message);

        log.info("ReAct RootNode - 初始化完成 sessionId={}, userId={}, agentId={}, terminalSessionId={}",
                sessionId, userId, agentId, terminalSessionId);

        // 6. 路由到 AI 调用节点
        return router(requestParameter, dynamicContext);
    }

    /**
     * 从配置播种护栏参数，并记录墙钟起点。抽为包级方法以便单测直接驱动（无需活容器）。
     */
    void seedDynamicContext(DefaultReActFactory.DynamicContext dynamicContext) {
        dynamicContext.setMaxSteps(reactProperties.getMaxSteps());
        dynamicContext.setMaxToolCalls(reactProperties.getMaxToolCalls());
        dynamicContext.setMaxToolCallsPerRound(reactProperties.getMaxToolCallsPerRound());
        dynamicContext.setWallClockTimeoutMillis(reactProperties.getWallClockTimeoutMillis());
        dynamicContext.setLoopStartEpochMilli(clock.millis());
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }
}
