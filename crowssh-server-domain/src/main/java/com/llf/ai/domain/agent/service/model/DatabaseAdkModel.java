package com.llf.ai.domain.agent.service.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 数据库工具由 ADK 携带可信 ToolContext 执行；补齐 ADK 1.2 遗漏的工具响应转换。 */
public final class DatabaseAdkModel extends BaseLlm {
    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageConverter converter = new MessageConverter(mapper);

    public DatabaseAdkModel(ChatModel chatModel, String modelName) {
        super(modelName);
        this.chatModel = Objects.requireNonNull(chatModel);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        try {
            Prompt prompt = toPrompt(request);
            if (stream) {
                return Flowable.fromPublisher(chatModel.stream(prompt))
                        .map(response -> converter.toLlmResponse(response, true));
            }
            return Flowable.just(converter.toLlmResponse(chatModel.call(prompt)));
        } catch (RuntimeException error) {
            return Flowable.error(error);
        }
    }

    private Prompt toPrompt(LlmRequest request) {
        Prompt converted = converter.toLlmPrompt(request);
        if (!(converted.getOptions() instanceof ToolCallingChatOptions source)) {
            throw new IllegalStateException("数据库 Agent 缺少受控工具定义");
        }
        ToolCallingChatOptions options = source.copy();
        // Spring AI 内部执行会调用 tool.runAsync(args, null)，丢失本轮 RunConfig 身份。
        options.setInternalToolExecutionEnabled(false);

        List<Content> contents = request.contents().stream()
                .filter(content -> !"system".equalsIgnoreCase(content.role().orElse("user"))).toList();
        long convertedCount = converted.getInstructions().stream().filter(message -> !(message instanceof SystemMessage)).count();
        if (convertedCount != contents.size()) {
            throw new IllegalStateException("数据库模型消息转换契约已变化");
        }
        List<Message> messages = new ArrayList<>();
        int index = 0;
        for (Message message : converted.getInstructions()) {
            if (message instanceof SystemMessage) {
                messages.add(message);
                continue;
            }
            Content content = contents.get(index++);
            var responses = content.parts().orElse(List.of()).stream()
                    .flatMap(part -> part.functionResponse().stream()).toList();
            if (responses.isEmpty()) {
                messages.add(message);
                continue;
            }
            if (!(message instanceof UserMessage user)) {
                throw new IllegalStateException("数据库工具响应角色不匹配");
            }
            var toolResponses = responses.stream().map(response -> new ToolResponseMessage.ToolResponse(
                    response.id().orElseThrow(() -> new IllegalStateException("数据库工具响应缺少调用 ID")),
                    response.name().orElseThrow(() -> new IllegalStateException("数据库工具响应缺少工具名")),
                    json(response.response().orElse(Map.of())))).toList();
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            // 工具结果不再变成空 user 消息；同一 Content 的文字或媒体仍保留。
            if (user.getText() != null && !user.getText().isBlank() || !user.getMedia().isEmpty()) messages.add(user);
        }
        return new Prompt(messages, options);
    }

    private String json(Map<String, Object> response) {
        try { return mapper.writeValueAsString(response); }
        catch (JsonProcessingException error) { throw new IllegalStateException("数据库工具响应无法序列化", error); }
    }

    @Override public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException("数据库 Agent 不支持实时双向模型连接");
    }
}
