package com.llf.ai.domain.agent.service.armory.matter.session;

import com.google.adk.agents.BaseAgent;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.Runner;
import com.llf.ai.domain.agent.service.IChatContextService;
import com.llf.ai.domain.agent.service.IPromptService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 创建带 CrowSSH 会话治理能力的通用 ADK Runner。 */
@Service
public class ManagedRunnerFactory {

    private final IChatContextService chatContextService;
    private final IPromptService promptService;

    public ManagedRunnerFactory(
            IChatContextService chatContextService,
            IPromptService promptService
    ) {
        this.chatContextService = chatContextService;
        this.promptService = promptService;
    }

    public Runner create(BaseAgent agent, String appName, List<? extends BasePlugin> plugins) {
        ManagedSessionService sessionService = new ManagedSessionService(sessionId -> {
            chatContextService.clearSessionContext(sessionId);
            promptService.clearMilestones(sessionId);
        });

        return Runner.builder()
                .agent(agent)
                .appName(appName)
                .sessionService(sessionService)
                .memoryService(new InMemoryMemoryService())
                .plugins(plugins)
                .build();
    }
}
