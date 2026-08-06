package com.llf.ai.domain.agent.service.model;

import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RuntimeRoutingChatModel implements ChatModel {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeRoutingChatModel.class);

    private final ChatModel defaultModel;
    private final RuntimeChatModelService runtimeChatModelService;

    public RuntimeRoutingChatModel(ChatModel defaultModel,
                                   RuntimeChatModelService runtimeChatModelService) {
        this.defaultModel = defaultModel;
        this.runtimeChatModelService = runtimeChatModelService;
    }

    private ChatModel delegate() {
        if (RuntimeChatModelContext.config() == null) {
            return defaultModel;
        }
        return RuntimeChatModelContext.getOrCreate(
                defaultModel,
                () -> runtimeChatModelService.build(
                        RuntimeChatModelContext.config(),
                        inheritedToolCallbacks()
                )
        );
    }

    private List<ToolCallback> inheritedToolCallbacks() {
        ChatOptions options = defaultModel.getDefaultOptions();
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null) {
            return List.copyOf(toolOptions.getToolCallbacks());
        }
        return List.of();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatModel selectedModel = delegate();
        logRequest(prompt, selectedModel, "chat");
        ChatResponse response = selectedModel.call(prompt);
        logResponse(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatModel selectedModel = delegate();
        logRequest(prompt, selectedModel, "stream");
        return Flux.defer(() -> {
            ResponseSummary summary = new ResponseSummary();
            return selectedModel.stream(prompt)
                    .doOnNext(summary::accept)
                    .doOnComplete(summary::write);
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate().getDefaultOptions();
    }

    private void logRequest(Prompt prompt, ChatModel selectedModel, String type) {
        RuntimeModelConfig config = RuntimeChatModelContext.config();
        ChatOptions promptOptions = prompt == null ? null : prompt.getOptions();
        ChatOptions defaultOptions = selectedModel.getDefaultOptions();
        String defaultModel = defaultOptions == null ? null : defaultOptions.getModel();
        LOGGER.info(
                "运行时模型请求: type={}, provider={}, protocol={}, model={}, delegate={}, toolCount={}, internalToolExecution={}",
                type,
                config == null ? "bootstrap" : safe(config.getProvider()),
                config == null ? "bootstrap" : safe(config.getProtocol()),
                config == null ? safe(defaultModel) : safe(config.getModel()),
                selectedModel.getClass().getSimpleName(),
                resolvedToolCount(promptOptions, defaultOptions),
                resolvedInternalToolExecution(promptOptions, defaultOptions)
        );
    }

    private void logResponse(ChatResponse response) {
        ResponseSummary summary = new ResponseSummary();
        summary.accept(response);
        summary.write();
    }

    private int resolvedToolCount(ChatOptions promptOptions, ChatOptions defaultOptions) {
        int promptCount = toolCount(promptOptions);
        return promptCount > 0 ? promptCount : toolCount(defaultOptions);
    }

    private int toolCount(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions toolOptions
                && toolOptions.getToolCallbacks() != null) {
            return toolOptions.getToolCallbacks().size();
        }
        return 0;
    }

    private boolean resolvedInternalToolExecution(ChatOptions promptOptions, ChatOptions defaultOptions) {
        if (promptOptions instanceof ToolCallingChatOptions promptToolOptions
                && promptToolOptions.getInternalToolExecutionEnabled() != null) {
            return Boolean.TRUE.equals(promptToolOptions.getInternalToolExecutionEnabled());
        }
        if (defaultOptions instanceof ToolCallingChatOptions defaultToolOptions
                && defaultToolOptions.getInternalToolExecutionEnabled() != null) {
            return Boolean.TRUE.equals(defaultToolOptions.getInternalToolExecutionEnabled());
        }
        return ToolCallingChatOptions.DEFAULT_TOOL_EXECUTION_ENABLED;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
    }

    private final class ResponseSummary {

        private final Set<String> toolCallIds = new LinkedHashSet<>();
        private final Set<String> finishReasons = new LinkedHashSet<>();
        private int anonymousToolCalls;
        private String upstreamModel;
        private boolean receivedResponse;

        private void accept(ChatResponse response) {
            if (response == null) {
                return;
            }
            receivedResponse = true;
            if (response.getMetadata() != null
                    && response.getMetadata().getModel() != null
                    && !response.getMetadata().getModel().isBlank()) {
                upstreamModel = response.getMetadata().getModel();
            }

            List<Generation> generations = response.getResults() == null
                    ? List.of()
                    : response.getResults();
            for (Generation generation : generations) {
                if (generation == null) {
                    continue;
                }
                String finishReason = generation.getMetadata() == null
                        ? null
                        : generation.getMetadata().getFinishReason();
                if (finishReason != null && !finishReason.isBlank()) {
                    finishReasons.add(finishReason);
                }
                if (generation.getOutput() == null || generation.getOutput().getToolCalls() == null) {
                    continue;
                }
                generation.getOutput().getToolCalls().forEach(toolCall -> {
                    if (toolCall == null) {
                        return;
                    }
                    String identity = toolCall.id();
                    if (identity == null || identity.isBlank()) {
                        identity = toolCall.name();
                    }
                    if (identity == null || identity.isBlank()) {
                        anonymousToolCalls++;
                    } else {
                        toolCallIds.add(identity);
                    }
                });
            }
        }

        private void write() {
            if (!receivedResponse) {
                LOGGER.warn("运行时模型响应为空");
                return;
            }
            String finishReason = finishReasons.isEmpty()
                    ? "unknown"
                    : safe(String.join(",", finishReasons));
            LOGGER.info(
                    "运行时模型响应: upstreamModel={}, returnedToolCalls={}, finishReason={}",
                    safe(upstreamModel),
                    toolCallIds.size() + anonymousToolCalls,
                    finishReason
            );
        }
    }
}
