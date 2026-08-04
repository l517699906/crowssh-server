package com.llf.ai.domain.agent.service.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;

public class RuntimeRoutingChatModel implements ChatModel {

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
        return delegate().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate().getDefaultOptions();
    }
}
