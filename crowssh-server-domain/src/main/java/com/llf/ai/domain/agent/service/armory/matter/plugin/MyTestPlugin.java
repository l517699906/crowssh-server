package com.llf.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service("myTestPlugin")
public class MyTestPlugin extends BasePlugin {

    public MyTestPlugin(String name) {
        super(name);
    }

    public MyTestPlugin() {
        super("MyTestPlugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(InvocationContext invocationContext, Content userMessage) {
        log.info("用户输入信息:{}", userMessage.text());
        return super.onUserMessageCallback(invocationContext, userMessage);
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        String agentName = agent.name();
        log.info("智能体名称:{}", agentName);
        return super.beforeAgentCallback(agent, callbackContext);
    }
}
