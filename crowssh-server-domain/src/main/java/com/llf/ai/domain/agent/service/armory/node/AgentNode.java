package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.tools.FunctionTool;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
                    .model(new SpringAI(chatModel))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // 构建 ADK 工具列表
            List<Object> adkTools = new ArrayList<>();

            // 添加 SSH 执行工具（ADK 原生 FunctionTool）
            try {
                log.info("开始创建 SSH 执行工具, sshExecuteAdkTool={}", sshExecuteAdkTool);
                FunctionTool sshTool = FunctionTool.create(sshExecuteAdkTool, "executeCommand");
                log.info("FunctionTool 创建成功: name={}, declaration={}",
                        sshTool.name(),
                        sshTool.declaration().isPresent() ? sshTool.declaration().get() : "null");
                adkTools.add(sshTool);
                log.info("为 Agent [{}] 注册 SSH 执行工具成功", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建 SSH ADK 工具失败", e);
            }

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

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }
}
