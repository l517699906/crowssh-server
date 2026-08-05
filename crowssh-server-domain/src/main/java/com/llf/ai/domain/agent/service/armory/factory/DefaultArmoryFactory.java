package com.llf.ai.domain.agent.service.armory.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.node.RootNode;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DefaultArmoryFactory {

    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    public AiAgentRegisterVO getAiAgentRegisterVO(String agentId) {
        return applicationContext.getBean(agentId, AiAgentRegisterVO.class);
    }

    /**
     * 定义一个上下文对象，用于各个节点串联的时候，写入数据和使用数据
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext {

        /**
         * LLM Api
         */
        private OpenAiApi openAiApi;

        /**
         * LLM ChatModel
         */
        private ChatModel chatModel;

        /**
         * ChatModel 节点创建、等待 Agent 节点注册到 ADK 的 Spring AI 工具。
         */
        @Builder.Default
        private List<ToolCallback> toolCallbacks = new ArrayList<>();

        /**
         * 智能体配置组
         */
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        /**
         * 原子安全的递进步骤
         */
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /**
         * 当前的智能体
         */
        private AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow;

        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

        public List<BaseAgent> queryAgentList(List<String> agentNames) {
            if (agentNames == null || agentNames.isEmpty() || agentGroup == null) {
                return Collections.emptyList();
            }

            List<BaseAgent> agents = new ArrayList<>();
            for (String name : agentNames) {
                BaseAgent baseAgent = agentGroup.get(name);
                if (baseAgent != null) {
                    agents.add(baseAgent);
                }
            }
            return agents;
        }

        public void addCurrentStepIndex() {
            currentStepIndex.incrementAndGet();
        }

        public int getCurrentStepIndex() {
            return currentStepIndex.get();
        }
    }
}
