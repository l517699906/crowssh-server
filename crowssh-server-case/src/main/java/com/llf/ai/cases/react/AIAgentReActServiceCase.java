package com.llf.ai.cases.react;

import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.IAIAgentReActServiceCase;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.cases.react.node.RootNode;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;

/**
 * AI 智能体 ReAct 执行服务实现
 *
 * <p>职责：
 * - 流式对话（SSE）：创建 emitter → 创建动态上下文 → 走节点链路
 * - 普通对话（非流式）：直接调用节点链路
 *
 * <p>节点链路：
 * RootNode → AiCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author llf
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        // 1. 创建 SSE 发射器（3 分钟超时）
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(3 * 60 * 1000L);

        try {
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getTerminalSessionId());

            // 2. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();

            // 3. 异步执行节点链路（避免阻塞 HTTP 线程）
            Thread streamThread = getThread(requestDTO, dynamicContext, emitter);
            streamThread.start();

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @NotNull
    private Thread getThread(ChatRequestDTO requestDTO, DefaultReActFactory.DynamicContext dynamicContext, ResponseBodyEmitter emitter) {
        Thread streamThread = new Thread(() -> {
            try {
                ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
                log.info("ReAct 流式对话完成 - 步数:{}, 工具调用:{}, stopReason:{}",
                        result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
            } catch (Exception e) {
                log.error("ReAct 流式对话异常", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        }, "react-stream-" + requestDTO.getSessionId());
        streamThread.setDaemon(true);
        return streamThread;
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new ResponseBodyEmitter(60 * 1000L))
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);
            return result.getContent();

        } catch (Exception e) {
            log.error("ReAct 普通对话异常", e);
            return "Error: " + e.getMessage();
        }
    }
}
