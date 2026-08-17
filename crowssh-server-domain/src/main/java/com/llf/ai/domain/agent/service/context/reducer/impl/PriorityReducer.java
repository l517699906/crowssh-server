package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 优先级裁剪器
 * <p>
 * 功能：按消息内容推断优先级，预算不足时优先丢弃"不重要"的消息，
 * 保证错误信息、关键指令不被裁掉。重要性保障。
 * <p>
 * 优先级规则（inferPriority）：
 * <pre>
 *   CRITICAL : tool 结果含 error/failed/exception/permission denied
 *              （错误必须让模型看到，否则会在同一个坑反复失败）
 *   HIGH     : system 消息；user 消息含 / 、.conf、.yml、.properties
 *              （通常是文件路径/配置类关键指令）
 *   LOW      : assistant 回复 > 5000 字符（多为冗长输出，可丢）
 *   MEDIUM   : 其余消息
 * </pre>
 * 运行过程：
 * <pre>
 *   messages --> 按工具调用/结果关系构造完整消息组
 *        |
 *        v
 *   取组内最高优先级，并按优先级、时间倒序排序
 *        |
 *        v
 *   usedTokens + groupTokens <= 预算 ? 保留整组 : 丢弃整组
 *        |
 *        v
 *   返回 kept（完整消息组，保持时间正序）
 * </pre>
 *
 * @author llf
 */
@Component
public class PriorityReducer implements MessageReducer {

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }

        List<MessageGroupSupport.MessageGroup> candidates = new ArrayList<>(
                MessageGroupSupport.group(messages));
        candidates.sort(Comparator
                .comparingInt(this::priorityWeight)
                .reversed()
                .thenComparing(
                        Comparator.comparingInt(MessageGroupSupport.MessageGroup::startIndex)
                                .reversed()));

        List<MessageGroupSupport.MessageGroup> kept = new ArrayList<>();
        int usedTokens = 0;
        for (MessageGroupSupport.MessageGroup group : candidates) {
            int groupTokens = MessageGroupSupport.estimateTokens(group);
            if (usedTokens + groupTokens <= tokenBudget) {
                kept.add(group);
                usedTokens += groupTokens;
            }
        }

        return MessageGroupSupport.flatten(kept);
    }

    int priorityWeight(MessageGroupSupport.MessageGroup group) {
        return group.messages().stream()
                .map(this::inferPriority)
                .mapToInt(Priority::weight)
                .max()
                .orElse(Priority.MEDIUM.weight());
    }

    private Priority inferPriority(Map<String, Object> message) {
        String role = stringValue(message.get("role"));
        String content = MessageGroupSupport.contentText(message);

        if (MessageGroupSupport.isToolResult(message)
                && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
        }
        if (MessageGroupSupport.isToolCallAssistant(message)) {
            return Priority.HIGH;
        }
        if ("user".equals(role) && containsAny(content, "/", ".conf", ".yml", ".properties")) {
            return Priority.HIGH;
        }
        if ("system".equals(role)) {
            return Priority.HIGH;
        }
        if ("assistant".equals(role) && content.length() > 5000) {
            return Priority.LOW;
        }
        return Priority.MEDIUM;
    }

    private boolean containsAny(String content, String... keywords) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private enum Priority {
        CRITICAL(100),
        HIGH(80),
        MEDIUM(50),
        LOW(20);

        private final int weight;

        Priority(int weight) {
            this.weight = weight;
        }

        int weight() {
            return weight;
        }
    }

}
