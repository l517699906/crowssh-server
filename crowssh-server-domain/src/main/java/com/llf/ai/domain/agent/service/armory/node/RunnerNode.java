package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.common.collect.ImmutableList;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.types.enums.ResponseCode;
import com.llf.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 执行节点
 *
 * @author llf
 * @date 2026/06/06
 */
@Slf4j
@Service
public class RunnerNode extends AbstractArmorySupport {
    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        // 入参对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        AiAgentConfigTableVO.Agent agent = aiAgentConfigTableVO.getAgent();
        String agentId = agent.getAgentId();
        String agentName = agent.getAgentName();
        String agentDesc = agent.getAgentDesc();

        // Runner 运行体
        InMemoryRunner runner = getRunner(dynamicContext, aiAgentConfigTableVO, appName);

        // 构建注册对象
        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .build();

        // 注册到 Spring 容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return aiAgentRegisterVO;
    }

    private InMemoryRunner getRunner(DefaultArmoryFactory.DynamicContext dynamicContext, AiAgentConfigTableVO aiAgentConfigTableVO, String appName) {
        AiAgentConfigTableVO.Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        String agentName = runnerConfig.getAgentName();
        if (StringUtils.isBlank(agentName)) {
            log.error("runner.agentName is null");
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }

        // 获取智能体
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        // 扩展插件
        List<BasePlugin> plugins;
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        if (null != pluginNameList && !pluginNameList.isEmpty()) {
            plugins = new ArrayList<>();
            for (String pluginName : pluginNameList) {
                BasePlugin plugin = getBean(pluginName);
                plugins.add(plugin);
            }
        } else {
            plugins = ImmutableList.of();
        }

        // 会话运行节点
        return new InMemoryRunner(baseAgent, appName, plugins);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }
}
