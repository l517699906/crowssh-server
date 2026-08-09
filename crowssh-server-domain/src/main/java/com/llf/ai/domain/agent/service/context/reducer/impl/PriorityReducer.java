package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 *   messages --> 逐条推断优先级 PrioritizedMessage(msg, priority)
 *        |
 *        v
 *   保底：先无条件保留最近 2 条
 *        |
 *        v
 *   从旧到新（倒数第3条 -> 第1条）尝试回填：
 *        usedTokens + msgTokens <= 预算 ? 加入头部 : 丢弃
 *        |
 *        v
 *   返回 kept（保持时间正序）
 * </pre>
 *
 * @author llf
 */
@Component
public class PriorityReducer implements MessageReducer {

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        // 为每条消息推断优先级
        List<PrioritizedMessage> prioritized = messages.stream()
            .map(m -> new PrioritizedMessage(m, inferPriority(m)))
            .collect(Collectors.toList());

        // 至少保留最近 2 条
        int minKeep = Math.min(2, prioritized.size());
        List<PrioritizedMessage> kept = new ArrayList<>(prioritized.subList(
            prioritized.size() - minKeep, prioritized.size()));

        // 从低优先级开始丢弃，直到满足 token 预算
        int usedTokens = estimateTokens(kept);
        for (int i = prioritized.size() - minKeep - 1; i >= 0; i--) {
            PrioritizedMessage pm = prioritized.get(i);
            int msgTokens = estimateToken(pm.getMessage());
            if (usedTokens + msgTokens <= tokenBudget) {
                kept.add(0, pm);
                usedTokens += msgTokens;
            }
        }

        return kept.stream().map(PrioritizedMessage::getMessage).collect(Collectors.toList());
    }

    private Priority inferPriority(Map<String, Object> message) {
        String role = (String) message.get("role");
        String content = String.valueOf(message.get("content"));

        if ("tool".equals(role) && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
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

    private int estimateToken(Map<String, Object> message) {
        String content = String.valueOf(message.get("content"));
        // 粗略估算：每 2 个字符 1 个 token
        return content != null ? content.length() / 2 : 0;
    }

    private int estimateTokens(List<PrioritizedMessage> messages) {
        return messages.stream().mapToInt(m -> estimateToken(m.getMessage())).sum();
    }

    @Data
    @AllArgsConstructor
    private static class PrioritizedMessage {
        private Map<String, Object> message;
        private Priority priority;
    }

    enum Priority { CRITICAL, HIGH, MEDIUM, LOW }

}
