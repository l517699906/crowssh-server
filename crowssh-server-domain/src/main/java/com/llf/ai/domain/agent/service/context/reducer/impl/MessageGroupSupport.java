package com.llf.ai.domain.agent.service.context.reducer.impl;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** reducer 共享的消息分组与 token 估算工具。 */
final class MessageGroupSupport {

    private MessageGroupSupport() {
    }

    static List<MessageGroup> group(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<MessageGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            Set<String> toolCallIds = extractToolCallIds(message);
            if (isAssistantMessage(message) && !toolCallIds.isEmpty()) {
                List<Map<String, Object>> groupedMessages = new ArrayList<>();
                groupedMessages.add(message);

                int next = index + 1;
                while (next < messages.size()
                        && isMatchingToolResult(messages.get(next), toolCallIds)) {
                    groupedMessages.add(messages.get(next));
                    next++;
                }
                groups.add(new MessageGroup(index, groupedMessages));
                index = next;
                continue;
            }

            groups.add(new MessageGroup(index, List.of(message)));
            index++;
        }
        return groups;
    }

    static List<Map<String, Object>> flatten(Collection<MessageGroup> groups) {
        return groups.stream()
                .sorted(Comparator.comparingInt(MessageGroup::startIndex))
                .flatMap(group -> group.messages().stream())
                .toList();
    }

    static Set<Integer> groupStartsForSubset(
            List<Map<String, Object>> subset,
            List<MessageGroup> allGroups
    ) {
        Set<Map<String, Object>> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(subset);

        Set<Integer> starts = new LinkedHashSet<>();
        for (MessageGroup group : allGroups) {
            if (group.messages().stream().anyMatch(identities::contains)) {
                starts.add(group.startIndex());
            }
        }
        return starts;
    }

    static int estimateTokens(List<Map<String, Object>> messages) {
        return messages.stream().mapToInt(MessageGroupSupport::estimateTokens).sum();
    }

    static int estimateTokens(MessageGroup group) {
        return estimateTokens(group.messages());
    }

    static int estimateTokens(Map<String, Object> message) {
        int characterCount = deepCharacterCount(message);
        return Math.max(1, (characterCount + 1) / 2);
    }

    static boolean isAssistantMessage(Map<String, Object> message) {
        return "assistant".equals(stringValue(message.get("role")));
    }

    static boolean isToolCallAssistant(Map<String, Object> message) {
        return isAssistantMessage(message) && !extractToolCallIds(message).isEmpty();
    }

    static boolean isToolResult(Map<String, Object> message) {
        if ("tool".equals(stringValue(message.get("role")))
                || "tool_result".equals(stringValue(message.get("type")))) {
            return true;
        }
        Object content = message.get("content");
        if (!(content instanceof List<?> blocks)) {
            return false;
        }
        return blocks.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(block -> "tool_result".equals(stringValue(block.get("type"))));
    }

    static String contentText(Map<String, Object> message) {
        Object content = message.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private static Set<String> extractToolCallIds(Map<String, Object> message) {
        Set<String> ids = new LinkedHashSet<>();

        Object toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> calls) {
            for (Object item : calls) {
                if (item instanceof Map<?, ?> call) {
                    addId(ids, call.get("id"));
                }
            }
        }

        Object content = message.get("content");
        if (content instanceof List<?> blocks) {
            for (Object item : blocks) {
                if (item instanceof Map<?, ?> block
                        && "tool_use".equals(stringValue(block.get("type")))) {
                    addId(ids, block.get("id"));
                }
            }
        }

        if ("tool_use".equals(stringValue(message.get("type")))) {
            addId(ids, message.get("id"));
        }
        return ids;
    }

    private static boolean isMatchingToolResult(
            Map<String, Object> message,
            Set<String> toolCallIds
    ) {
        Set<String> resultIds = new LinkedHashSet<>();
        if ("tool".equals(stringValue(message.get("role")))) {
            addId(resultIds, message.get("tool_call_id"));
        }
        if ("tool_result".equals(stringValue(message.get("type")))) {
            addId(resultIds, message.get("tool_use_id"));
        }

        Object content = message.get("content");
        if (content instanceof List<?> blocks) {
            for (Object item : blocks) {
                if (item instanceof Map<?, ?> block
                        && "tool_result".equals(stringValue(block.get("type")))) {
                    addId(resultIds, block.get("tool_use_id"));
                }
            }
        }

        return resultIds.stream().anyMatch(toolCallIds::contains);
    }

    private static void addId(Set<String> ids, Object value) {
        String id = stringValue(value);
        if (!id.isBlank()) {
            ids.add(id);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int deepCharacterCount(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.length();
        }
        if (value instanceof Map<?, ?> map) {
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                count += deepCharacterCount(entry.getKey());
                count += deepCharacterCount(entry.getValue());
            }
            return count;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object item : iterable) {
                count += deepCharacterCount(item);
            }
            return count;
        }
        if (value.getClass().isArray()) {
            int count = 0;
            for (int i = 0; i < Array.getLength(value); i++) {
                count += deepCharacterCount(Array.get(value, i));
            }
            return count;
        }
        return String.valueOf(value).length();
    }

    record MessageGroup(int startIndex, List<Map<String, Object>> messages) {
    }
}
