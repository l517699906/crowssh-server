package com.llf.ai.domain.agent.service.model;

import org.springframework.ai.chat.model.ChatModel;

final class RuntimeChatModelContext {

    private static final ThreadLocal<ChatModel> CURRENT = new ThreadLocal<>();

    private RuntimeChatModelContext() {
    }

    static ChatModel current() {
        return CURRENT.get();
    }

    static void set(ChatModel chatModel) {
        CURRENT.set(chatModel);
    }

    static void clear() {
        CURRENT.remove();
    }
}
