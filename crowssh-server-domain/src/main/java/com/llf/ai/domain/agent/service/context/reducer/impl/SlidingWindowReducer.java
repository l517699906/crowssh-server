package com.llf.ai.domain.agent.service.context.reducer.impl;

import com.llf.ai.domain.agent.service.context.reducer.MessageReducer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 滑动窗口裁剪器
 * <p>
 * 功能：保留"最近"的消息——从新到旧逐条装入窗口，
 * 受"窗口条数（20）+ token 预算"双重限制，任一超限即停止。
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
    private static final int DEFAULT_WINDOW_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        List<Map<String, Object>> window = new ArrayList<>();
        int usedTokens = 0;

        // 从新到旧逐条添加，直到超出 token 预算或窗口大小
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            int msgTokens = estimateToken(msg);
            if (window.size() >= DEFAULT_WINDOW_SIZE || usedTokens + msgTokens > tokenBudget) break;
            window.add(0, msg);
            usedTokens += msgTokens;
        }

        return window;
    }

    private int estimateToken(Map<String, Object> message) {
        String content = String.valueOf(message.get("content"));
        return content != null ? content.length() / 2 : 0;
    }

}
