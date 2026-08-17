package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * 滑动窗口裁剪器
 * <p>
 * 功能：保留"最近"的完整消息组——从新到旧按组装入窗口，
 * 受"窗口组数（20）+ token 预算"双重限制，任一超限即停止。
 * 时效性保障：最近的对话对当前推理最重要。
 * <p>
 * 运行过程：
 * <pre>
 *   messages:  [m0][m1][m2] ... [mN-2][mN-1]   （时间正序，右新左旧）
 *                                   <--------+
 *                                   从尾部向前逐条扫描
 *                     |
 *                     v
 *   每条消息: window.size() >= 20 ?            --是--> 停止
 *             usedTokens + msgTokens > 预算 ?  --是--> 停止
 *                     |否
 *                     v
 *             window.add(0, msg)   从头部插入，保持正序
 *                     |
 *                     v
 *   返回 window（最近 <=20 条且总 token 不超预算的消息）
 * </pre>
 * token 估算：粗略按 content.length()/2（2 个字符约 1 token）。
 *
 * @author llf
 */
@Component
public class SlidingWindowReducer implements MessageReducer {
    private static final int DEFAULT_WINDOW_GROUP_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty() || tokenBudget <= 0) {
            return List.of();
        }

        List<MessageGroupSupport.MessageGroup> groups = MessageGroupSupport.group(messages);
        ArrayDeque<MessageGroupSupport.MessageGroup> window = new ArrayDeque<>();
        int usedTokens = 0;

        for (int i = groups.size() - 1; i >= 0; i--) {
            MessageGroupSupport.MessageGroup group = groups.get(i);
            int groupTokens = MessageGroupSupport.estimateTokens(group);
            if (window.size() >= DEFAULT_WINDOW_GROUP_SIZE
                    || usedTokens + groupTokens > tokenBudget) {
                break;
            }
            window.addFirst(group);
            usedTokens += groupTokens;
        }

        return MessageGroupSupport.flatten(window);
    }

}
