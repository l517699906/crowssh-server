package com.llf.ai.domain.agent.service.model;

import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.DisposableBean;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class RuntimeChatModelContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeChatModelContext.class);
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private RuntimeChatModelContext() {
    }

    static RuntimeModelConfig config() {
        State state = CURRENT.get();
        return state == null ? null : state.config;
    }

    static ChatModel getOrCreate(ChatModel defaultModel, Supplier<ChatModel> factory) {
        State state = CURRENT.get();
        if (state == null) {
            return defaultModel;
        }
        return state.models.computeIfAbsent(defaultModel, ignored -> factory.get());
    }

    static void set(RuntimeModelConfig config) {
        clear();
        CURRENT.set(new State(config));
    }

    static void clear() {
        State state = CURRENT.get();
        CURRENT.remove();
        if (state == null) {
            return;
        }
        for (ChatModel model : state.models.values()) {
            if (model instanceof DisposableBean disposable) {
                try {
                    disposable.destroy();
                } catch (Exception error) {
                    LOGGER.debug("关闭请求级 AI 模型失败", error);
                }
            }
        }
        state.models.clear();
    }

    private static final class State {
        private final RuntimeModelConfig config;
        private final Map<ChatModel, ChatModel> models = new IdentityHashMap<>();

        private State(RuntimeModelConfig config) {
            this.config = config;
        }
    }
}
