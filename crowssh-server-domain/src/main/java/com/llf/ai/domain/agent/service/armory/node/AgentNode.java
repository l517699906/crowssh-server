package com.llf.ai.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.llf.ai.domain.agent.model.entity.ArmoryCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import com.llf.ai.domain.agent.service.armory.AbstractArmorySupport;
import com.llf.ai.domain.agent.service.armory.catalog.AgentCatalog;
import com.llf.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
    @Resource
    private DbQueryAdkTool dbQueryAdkTool;

    @Resource
    private AgentCatalog agentCatalog;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    private static final java.util.Set<String> READ_ONLY_TOOLS = java.util.Set.of(
            "inspectSystem", "inspectDisk", "inspectNetwork", "inspectService",
            "listRemoteFiles", "readRemoteText", "statRemotePath", "compareRemoteTexts");

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 — AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String resourceKind = aiAgentConfigTableVO.getAgent().getResourceKind();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        Map<String, AiAgentConfigTableVO.Module.Agent> configs = new LinkedHashMap<>();
        for (var config : agents) {
            if (config.getName() == null || configs.putIfAbsent(config.getName(), config) != null)
                throw new IllegalArgumentException("Agent 名称缺失或重复");
        }
        for (var config : agents) {
            List<Object> tools = configuredTools(dynamicContext.getToolCallbacks(), resourceKind, config.isReadOnly(), false);
            List<String> children = config.getSubAgents();
            if (children != null && !children.isEmpty()) {
                // DB 的轮次、独立 lane 和审批不能复用 SSH 子任务作用域。
                if (!"SSH".equals(resourceKind))
                    throw new IllegalArgumentException("当前仅 SSH Agent 支持子任务派发");
                Map<String, BaseAgent> childAgents = new LinkedHashMap<>();
                java.util.Set<String> readOnly = new java.util.HashSet<>();
                for (String name : children) {
                    var child = configs.get(name);
                    if (child == null || name.equals(config.getName()) || childAgents.containsKey(name)
                            || (child.getSubAgents() != null && !child.getSubAgents().isEmpty()))
                        throw new IllegalArgumentException("子 Agent 必须存在、唯一且不再派发: " + name);
                    childAgents.put(name, buildAgent(child, chatModel, resourceKind,
                            configuredTools(dynamicContext.getToolCallbacks(), resourceKind, child.isReadOnly(), true)));
                    if (child.isReadOnly()) readOnly.add(name);
                }
                String agentId = aiAgentConfigTableVO.getAgent().getAgentId();
                agentCatalog.register(agentId, config.getName(), childAgents, readOnly);
                var batch = new BatchSubAgentDispatchTool(agentId, config.getName(), children, dynamicAgentOrchestrator);
                tools.add(batch);
                for (String name : children)
                    tools.add(new SubAgentDispatchTool(name, configs.get(name).getDescription(), batch));
            }
            // 业务工具和派发工具一起构建，保留统一模型适配及观测配置。
            Map<String, BaseTool> unique = new LinkedHashMap<>();
            for (Object tool : tools) addUnique(unique, (BaseTool) tool);
            dynamicContext.getAgentGroup().put(config.getName(), buildAgent(config, chatModel, resourceKind, List.copyOf(unique.values())));
        }
        return router(requestParameter, dynamicContext);
    }

    private LlmAgent buildAgent(AiAgentConfigTableVO.Module.Agent config, ChatModel model, String resourceKind, List<?> tools) {
        var builder = LlmAgent.builder()
                .name(config.getName())
                .description(config.getDescription())
                .model(createAdkModel(model, resourceKind))
                .instruction(config.getInstruction())
                .tools(tools);
        if (config.getOutputKey() != null && !config.getOutputKey().isBlank()) builder.outputKey(config.getOutputKey());
        return builder.build();
    }

    List<Object> configuredTools(List<ToolCallback> callbacks, String resourceKind, boolean readOnly, boolean child) {
        List<Object> tools = new ArrayList<>();
        for (Object tool : createAdkTools(callbacks, resourceKind)) {
            BaseTool baseTool = (BaseTool) tool;
            if (readOnly) {
                boolean allowed = "SSH".equals(resourceKind) ? READ_ONLY_TOOLS.contains(baseTool.name())
                        : java.util.Set.of("listDatabases", "listTables", "describeTable", "explainQuery",
                                "inspectInstance", "inspectProcessList", "inspectLocks").contains(baseTool.name());
                if (!allowed) continue;
            }
            tools.add(child ? new SubAgentGuardedTool(baseTool) : baseTool);
        }
        return tools;
    }

    List<Object> createAdkTools(List<ToolCallback> toolCallbacks) {
        return createAdkTools(toolCallbacks, "SSH");
    }

    List<Object> createAdkTools(List<ToolCallback> toolCallbacks, String resourceKind) {
        Map<String, BaseTool> toolsByName = new LinkedHashMap<>();
        if ("DB".equals(resourceKind)) {
            addUnique(toolsByName, FunctionTool.create(dbQueryAdkTool, "executeQuery"));
        } else if ("SSH".equals(resourceKind)) {
            addUnique(toolsByName, FunctionTool.create(sshExecuteAdkTool, "executeCommand"));
        } else throw new IllegalArgumentException("不支持的 Agent 资源类型");

        if (toolCallbacks != null) {
            for (ToolCallback toolCallback : toolCallbacks) {
                if ("DB".equals(resourceKind) && (!(toolCallback instanceof DatabaseCompatibleToolCallback)
                        || !java.util.Set.of("Skill", "listDatabases", "listTables",
                        "describeTable", "explainQuery", "inspectInstance", "inspectProcessList", "inspectLocks")
                        .contains(toolCallback.getToolDefinition().name()))) {
                    throw new IllegalArgumentException("数据库 Agent 不允许装配此工具");
                }
                addUnique(toolsByName, new SpringAiToolCallbackAdkAdapter(toolCallback));
            }
        }

        log.info("ADK 工具注册完成: count={}, names={}", toolsByName.size(), toolsByName.keySet());
        if ("DB".equals(resourceKind)) {
            return toolsByName.values().stream().map(tool -> (Object)
                    new com.llf.ai.domain.agent.service.armory.matter.tools.DatabaseBoundTool(tool)).toList();
        }
        return List.copyOf(toolsByName.values());
    }

    private void addUnique(Map<String, BaseTool> toolsByName, BaseTool tool) {
        BaseTool previous = toolsByName.putIfAbsent(tool.name(), tool);
        if (previous != null) {
            throw new IllegalArgumentException("ADK 工具名称重复: " + tool.name());
        }
    }

    com.google.adk.models.BaseLlm createAdkModel(ChatModel chatModel, String resourceKind) {
        String modelName = chatModel.getClass().getSimpleName()
                .toLowerCase()
                .replace("chatmodel", "")
                .replace("model", "");
        if ("DB".equals(resourceKind)) {
            return new com.llf.ai.domain.agent.service.model.DatabaseAdkModel(chatModel, modelName);
        }
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
