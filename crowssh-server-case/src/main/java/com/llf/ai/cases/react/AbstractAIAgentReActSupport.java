package com.llf.ai.cases.react;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActEventDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;


/**
 * ReAct 支撑类（抽象基类）
 *
 * <p>封装 ReAct 循环的通用能力：
 * - 上下文管理（DynamicContext）
 * - SSE 事件发射
 * - 工具调用结果解析
 * - 响应格式化
 *
 * <p>节点路由链：
 * RootNode → AiCallNode → ToolCallNode → (ToolResultNode) → [循环或完成]
 *
 * @author llf
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 会话 → 终端会话 ID 映射
     */
    protected static final Map<String, String> sessionTerminalMapping = new ConcurrentHashMap<>();

    /**
     * 当前线程绑定的终端会话 ID
     */
    protected static final InheritableThreadLocal<String> currentTerminalSession = new InheritableThreadLocal<>();

    @Override
    protected void multiThread(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 暂无异步预加载需求
    }

    /**
     * 通用的 Bean 获取
     */
    @SuppressWarnings("unchecked")
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    // ═══════════════════════════════════════════════════════════════
    //  上下文绑定（ThreadLocal 方式，兼容异步线程）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置当前线程的终端会话 ID
     */
    protected static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
    }

    /**
     * 获取当前线程的终端会话 ID
     */
    protected static String getCurrentTerminalSession() {
        return currentTerminalSession.get();
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    protected static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
    }

    /**
     * 绑定会话与终端会话
     */
    protected static void bindTerminalSession(String sessionId, String terminalSessionId) {
        if (sessionId != null && terminalSessionId != null) {
            sessionTerminalMapping.put(sessionId, terminalSessionId);
        }
    }

    /**
     * 获取会话绑定的终端会话 ID
     */
    protected static String getTerminalSession(String sessionId) {
        return sessionId != null ? sessionTerminalMapping.get(sessionId) : null;
    }

    /**
     * 解绑会话与终端会话
     */
    protected static void unbindTerminalSession(String sessionId) {
        if (sessionId != null) {
            sessionTerminalMapping.remove(sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送文本事件
     */
    protected void sendTextEvent(ResponseBodyEmitter emitter, String content, String fullText) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("text");
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送文本事件 {}", event);
        } catch (Exception e) {
            log.warn("发送文本事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具调用事件
     */
    protected void sendToolCallEvent(ResponseBodyEmitter emitter, String toolCallId, String toolName, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_call");
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具调用事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具调用事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送工具结果事件
     */
    protected void sendToolResultEvent(ResponseBodyEmitter emitter, String toolCallId, String content, String status) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_result");
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送工具结果事件 {}", event);
        } catch (Exception e) {
            log.warn("发送工具结果事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送步数结束事件
     */
    protected void sendRoundEndEvent(ResponseBodyEmitter emitter, int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setShouldContinue(shouldContinue);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_end");
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 round_end 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 round_end 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 发送完成事件
     */
    protected void sendDoneEvent(ResponseBodyEmitter emitter, ReActResultDTO result) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("done");
            event.setContent(objectMapper.writeValueAsString(result));
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("发送 done 事件 {}", event);
        } catch (Exception e) {
            log.warn("发送 done 事件失败: {}", e.getMessage());
        }
    }
}
