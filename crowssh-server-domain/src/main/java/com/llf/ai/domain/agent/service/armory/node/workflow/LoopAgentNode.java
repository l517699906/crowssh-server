package com.llf.ai.domain.agent.service.armory.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.model.valobj.enums.AgenttypeEnum;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("loopAgentNode")
public class LoopAgentNode extends AbstractArmorySupport {
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        List<String> subAgentNames = currentAgentWorkflow.getSubAgents();
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(subAgentNames);

        LoopAgent loopAgent =
                LoopAgent.builder()
                        .name(currentAgentWorkflow.getName())
                        .description(currentAgentWorkflow.getDescription())
                        .subAgents(subAgents)
                        .maxIterations(currentAgentWorkflow.getMaxIterations())
                        .build();

        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), loopAgent);

        return router(requestParameter, dynamicContext);

    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }
}
