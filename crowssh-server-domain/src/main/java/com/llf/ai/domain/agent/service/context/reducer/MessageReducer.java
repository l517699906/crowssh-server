package com.llf.ai.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

/**
 * 消息裁剪器接口
 * <p>
 * 功能：在 token 预算内对消息历史做裁剪，
 * 避免多轮对话 + 工具结果把 LLM 上下文窗口撑爆。
 * <p>
 * 体系架构：
 * <pre>
 *                 ChatContextService.trimHistory()
 *                          |
 *                          v
 *                    HybridReducer（组合策略，实际使用）
 *                     |              |
 *                     v              v
 *            PriorityReducer   SlidingWindowReducer
 *            （重要性：错误/     （时效性：最近20组
 *              关键指令不丢）     + token 预算）
 *                     +------┬-------+
 *                            |
 *                            v
 *             合并完整消息组 + 严格总预算 --> 裁剪后的历史
 * </pre>
 *
 * @author llf
 */
public interface MessageReducer {

    /**
     * 裁剪消息历史
     *
     * @param messages    原始消息列表（按时间正序）
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表（保持时间正序）
     */
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);

}
