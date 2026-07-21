package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.model.valobj.enums.AgenttypeEnum;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.node.workflow.LoopAgentNode;
import com.llf.ai.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import com.llf.ai.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AgentWorkflowNode extends AbstractArmorySupport {

    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private RunnerNode runnerNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 — AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.AgentWorkflow> agentWorkflows = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        // 如果未配置 agentWorkflows 则直接流转到 RunnerNode
        if (null == agentWorkflows || agentWorkflows.isEmpty() || dynamicContext.getCurrentStepIndex() >= agentWorkflows.size()) {
            // 清空结果值
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }

        dynamicContext.setCurrentAgentWorkflow(agentWorkflows.get(dynamicContext.getCurrentStepIndex()));
        // 步骤值增加
        dynamicContext.addCurrentStepIndex();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow) {
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgenttypeEnum agenttypeEnum = AgenttypeEnum.formType(type);

        if (null == agenttypeEnum) {
            throw new RuntimeException("agentWorkflow type is error!");
        }

        String node = agenttypeEnum.getNode();

        return switch (node) {
            case "loopAgentNode" -> loopAgentNode;
            case "parallelAgentNode" -> parallelAgentNode;
            case "sequentialAgentNode" -> sequentialAgentNode;
            default -> runnerNode;
        };
    }
}
