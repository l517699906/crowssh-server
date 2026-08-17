package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合裁剪器（ChatContextService 实际使用的裁剪策略）
 * <p>
 * 功能：组合两种裁剪器的优点——PriorityReducer 保证"重要的不丢"，
 * SlidingWindowReducer 保证"新的不丢"。先合并完整消息组候选，再执行一次
 * 统一 token 预算，避免两个子策略的并集突破总预算。
 * <p>
 * 运行过程：
 * <pre>
 *   messages ──+--> PriorityReducer.reduce()  --> 保留集合 A（按重要性）
 *              |
 *              +--> SlidingWindowReducer.reduce() --> 保留集合 B（按时效）
 *                          |
 *                          v
 *                  candidates = A ∪ B + 最近 2 个完整消息组
 *                          |
 *                          v
 *                  按最近性、重要性、时间倒序选择
 *                          |
 *                          v
 *                  在统一预算内按原顺序输出完整消息组
 * </pre>
 *
 * @author llf
 */
@Component
public class HybridReducer implements MessageReducer {

    private final PriorityReducer priorityReducer;
    private final SlidingWindowReducer slidingReducer;

    public HybridReducer(
            PriorityReducer priorityReducer,
            SlidingWindowReducer slidingReducer
    ) {
        this.priorityReducer = priorityReducer;
        this.slidingReducer = slidingReducer;
    }

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }

        List<MessageGroupSupport.MessageGroup> groups = MessageGroupSupport.group(messages);
        Set<Integer> candidateStarts = new LinkedHashSet<>();
        candidateStarts.addAll(MessageGroupSupport.groupStartsForSubset(
                priorityReducer.reduce(messages, tokenBudget), groups));
        candidateStarts.addAll(MessageGroupSupport.groupStartsForSubset(
                slidingReducer.reduce(messages, tokenBudget), groups));

        Set<Integer> recentStarts = new LinkedHashSet<>();
        int recentGroupCount = Math.min(2, groups.size());
        for (int i = groups.size() - recentGroupCount; i < groups.size(); i++) {
            int startIndex = groups.get(i).startIndex();
            recentStarts.add(startIndex);
            candidateStarts.add(startIndex);
        }

        List<MessageGroupSupport.MessageGroup> candidates = groups.stream()
                .filter(group -> candidateStarts.contains(group.startIndex()))
                .sorted(Comparator
                        .comparing((MessageGroupSupport.MessageGroup group) ->
                                recentStarts.contains(group.startIndex()))
                        .reversed()
                        .thenComparing(
                                Comparator.comparingInt(priorityReducer::priorityWeight)
                                        .reversed())
                        .thenComparing(
                                Comparator.comparingInt(MessageGroupSupport.MessageGroup::startIndex)
                                        .reversed()))
                .toList();

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

}
