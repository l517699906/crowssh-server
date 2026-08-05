package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.agent.service.armory.matter.tools.SpringAiToolCallbackAdkAdapter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 — AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for ( AiAgentConfigTableVO.Module.Agent agentConfig : agents ) {
            LlmAgent.Builder builder  = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(createAdkModel(chatModel))
                    .instruction(agentConfig.getInstruction());

            if (agentConfig.getOutputKey() != null && !agentConfig.getOutputKey().isBlank()) {
                builder.outputKey(agentConfig.getOutputKey());
            }

            List<Object> adkTools = createAdkTools(dynamicContext.getToolCallbacks());

            // 注册工具到 Agent
            if (!adkTools.isEmpty()) {
                log.info("为 Agent [{}] 注册 {} 个工具", agentConfig.getName(), adkTools.size());
                builder.tools(adkTools);
            } else {
                log.warn("Agent [{}] 没有注册任何工具！", agentConfig.getName());
            }

            LlmAgent llmAgent = builder.build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    List<Object> createAdkTools(List<ToolCallback> toolCallbacks) {
        Map<String, BaseTool> toolsByName = new LinkedHashMap<>();

        FunctionTool sshTool = FunctionTool.create(sshExecuteAdkTool, "executeCommand");
        addUnique(toolsByName, sshTool);

        if (toolCallbacks != null) {
            for (ToolCallback toolCallback : toolCallbacks) {
                addUnique(toolsByName, new SpringAiToolCallbackAdkAdapter(toolCallback));
            }
        }

        log.info("ADK 工具注册完成: count={}, names={}", toolsByName.size(), toolsByName.keySet());
        return List.copyOf(toolsByName.values());
    }

    private void addUnique(Map<String, BaseTool> toolsByName, BaseTool tool) {
        BaseTool previous = toolsByName.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("ADK 工具名称重复: " + tool.name());
        }
    }

    private SpringAI createAdkModel(ChatModel chatModel) {
        String modelName = chatModel.getClass().getSimpleName()
                .toLowerCase()
                .replace("chatmodel", "")
                .replace("model", "");
        return new SpringAI(chatModel, modelName, createAdkObservabilityConfig());
    }

    SpringAIProperties.Observability createAdkObservabilityConfig() {
        SpringAIProperties.Observability observability = new SpringAIProperties.Observability();
        observability.setEnabled(true);
        observability.setIncludeContent(false);
        // ADK 1.2.0 每次请求都会重复注册 token Gauge；保留日志，禁用该缺陷指标。
        observability.setMetricsEnabled(false);
        return observability;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }
}
