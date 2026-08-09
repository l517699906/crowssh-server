package com.llf.ai.domain.agent.service.armory.matter.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

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
        return Maybe.fromAction(() -> {
            int contentLength = userMessage == null || userMessage.text() == null
                    ? 0 : userMessage.text().length();
            log.info("插件日志-🚀 用户输入信息 | invocationId:{} | userId:{} | contentLength:{}",
                    invocationContext.invocationId(),
                    invocationContext.userId(),
                    contentLength);
        });
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🤖 智能体启动 | agentName:{} | invocationId:{}",
                    agent.name(),
                    callbackContext.invocationId());
        });
    }

    @Override
    public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🤖 智能体完成 | agentName:{} | invocationId:{}",
                    agent.name(),
                    callbackContext.invocationId());
        });
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
        return Maybe.fromAction(() -> {
            LlmRequest request = llmRequest.build();
            String toolNames = request.tools().isEmpty()
                    ? "无"
                    : String.join(", ", request.tools().keySet());
            log.info("插件日志-🧠 大模型请求 | agent:{} | model:{} | 可用工具:[{}]",
                    callbackContext.agentName(),
                    request.model().orElse("default"),
                    toolNames);
        });
    }

    @Override
    public Maybe<LlmResponse> afterModelCallback(CallbackContext callbackContext, LlmResponse llmResponse) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🧠 大模型响应 | agent:{} | contentLength:{} | turnComplete:{}",
                    callbackContext.agentName(),
                    contentLength(llmResponse.content()),
                    llmResponse.turnComplete().orElse(false));

            llmResponse.usageMetadata().ifPresent(usage -> {
                log.info("插件日志-🧠 Token 消耗 | input:{} | output:{}",
                        usage.promptTokenCount(),
                        usage.candidatesTokenCount());
            });
        });
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🔧 工具调用开始 | tool:{} | agent:{} | argumentCount:{}",
                    tool.name(),
                    toolContext.agentName(),
                    sizeOf(toolArgs));
        });
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Map<String, Object> result) {
        return Maybe.fromAction(() -> {
            log.info("插件日志-🔧 工具调用完成 | tool:{} | agent:{} | resultFieldCount:{}",
                    tool.name(),
                    toolContext.agentName(),
                    sizeOf(result));
        });
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
        return Maybe.fromAction(() -> {
            log.error("插件日志-🔧 工具调用异常 | tool:{} | agent:{} | argumentCount:{} | errorType:{}",
                    tool.name(),
                    toolContext.agentName(),
                    sizeOf(toolArgs),
                    error == null ? "unknown" : error.getClass().getName());
        });
    }

    private int contentLength(Optional<Content> contentOptional) {
        if (contentOptional == null || contentOptional.isEmpty()) {
            return 0;
        }
        Content content = contentOptional.get();
        if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
            return 0;
        }
        return content.parts().get().stream()
                .mapToInt(part -> part.text().orElse("").length())
                .sum();
    }

    private int sizeOf(Map<String, Object> values) {
        return values == null ? 0 : values.size();
    }
}
