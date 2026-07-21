package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.llf.ai.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取上下文对象
        OpenAiApi openAiApi = dynamicContext.getOpenAiApi();

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // 构建mcp/skills服务（工厂）
        List<ToolCallback> toolCallbackList = new ArrayList<>();
        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp:toolMcpList) {
                ToolMcpCreateService toolMcpCreateService = defaultMcpClientFactory.getToolMcpCreateService(toolMcp);
                ToolCallback[] toolCallbacks = toolMcpCreateService.buildToolCallback(toolMcp);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // 构建对话模型
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(chatModelConfig.getModel())
                        .toolCallbacks(toolCallbackList)
                        .build())
                .build();

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }
}
