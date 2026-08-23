package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
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

    /**
     * 负责按重要性筛选历史消息，并尽量保留关键错误、路径和工具调用链。
     */
    @Resource
    private PriorityReducer priorityReducer;

    /**
     * 负责按时间顺序保留最近的消息组，避免当前对话上下文断层。
     */
    @Resource
    private SlidingWindowReducer slidingReducer;

    public HybridReducer(
            PriorityReducer priorityReducer,
            SlidingWindowReducer slidingReducer
    ) {
        this.priorityReducer = priorityReducer;
        this.slidingReducer = slidingReducer;
    }

    /**
     * 执行混合裁剪，把“重要历史”和“近期上下文”合并为一份按原始顺序排列的消息列表。
     *
     * <p>这里采用"优先级主导 + 最近窗口补充 + 完整消息组保底"的组合策略：
     * <ul>
     *   <li>PriorityReducer 决定哪些历史消息最值得保留</li>
     *   <li>SlidingWindowReducer 提供最近上下文补充，避免主策略过度偏向旧关键消息</li>
     *   <li>最近 2 个完整消息组强制保底，确保当前轮上下文不被破坏</li>
     * </ul>
     *
     * <p>案例：
     * <pre>
     *   原始消息组：
     *   G1=[system: 你是运维助手]
     *   G2=[user: 查看 /etc/nginx/nginx.conf]
     *   G3=[assistant: 很长的分析说明（如果你用waliapi的话，可以看过调用过程中的日志信息）...]
     *   G4=[assistant(tool_calls), tool: permission denied]
     *   G5=[user: 那你改查 error.log]
     *   G6=[assistant(tool_calls), tool: tail 结果]
     *
     *   PriorityReducer 可能给出：
     *   A = [G1, G2, G4, G5]
     *
     *   SlidingWindowReducer 可能给出：
     *   B = [G4, G5, G6]
     *
     *   HybridReducer 合并过程：
     *   1. 先保留 A
     *   2. 再把 B 中 A 还没有的 G6 补进来
     *   3. 最后再强制保底最近 2 组（这里就是 G5、G6）
     *
     *   最终结果：
     *   [G1, G2, G4, G5, G6]
     * </pre>
     *
     * <p>这样可以同时避免两种极端：
     * 一种是只看优先级，导致最近上下文断层；
     * 另一种是只看最近窗口，导致关键错误和关键指令被冲掉。
     *
     * @param messages 原始消息列表
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表
     */
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
