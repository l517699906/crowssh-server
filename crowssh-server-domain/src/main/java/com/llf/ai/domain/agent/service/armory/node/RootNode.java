package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RootNode extends AbstractArmorySupport {

    @Resource
    private AiApiNode aiApiNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        // 路由到下一个节点
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        // 配置了下一个节点
        return aiApiNode;
    }

}
