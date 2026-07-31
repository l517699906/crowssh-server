package com.llf.ai.domain.agent.service.model;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

public class RuntimeRoutingChatModel implements ChatModel {

    private final ChatModel defaultModel;

    public RuntimeRoutingChatModel(ChatModel defaultModel) {
        this.defaultModel = defaultModel;
    }

    private ChatModel delegate() {
        ChatModel runtimeModel = RuntimeChatModelContext.current();
        return runtimeModel != null ? runtimeModel : defaultModel;
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
