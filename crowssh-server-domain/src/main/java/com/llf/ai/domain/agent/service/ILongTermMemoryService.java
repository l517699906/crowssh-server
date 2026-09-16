package com.llf.ai.domain.agent.service;

import com.llf.ai.domain.agent.model.entity.ChatMessageEntity;
import com.llf.ai.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆服务接口
 * <p>
 * 定义从对话流中自动提取结构化记忆、召回相关记忆、构建 Prompt 摘要的能力。
 * 由 {@code LongTermMemoryService} 实现，在 ReAct 循环各阶段被调用：
 * <ul>
 *   <li>{@link AiCallNode} 首轮调用 {@link #recordUserMessage} 提取用户偏好</li>
 *   <li>{@link ToolCallNode} 调用 {@link #recordToolObservation} 提取环境/软件/失败信号</li>
 *   <li>{@link AiCallNode} 调用 {@link #recordAssistantConclusion} 提取排查结论</li>
 *   <li>{@link LongTermMemoryProvider} 调用 {@link #buildMemorySummary} 构建注入 Prompt 的摘要</li>
 * </ul>
 *
 * @see com.llf.ai.domain.agent.service.memory.LongTermMemoryService
 */
public interface ILongTermMemoryService {

    /**
     * 记录用户消息，检测并提取用户偏好记忆（USER_PREFERENCE）。
     * <p>
     * 当用户消息包含"以后""默认""记住"等偏好词时，提取为偏好记忆。
     * 意图标签会拼入关键词，提高后续召回命中率。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     */
    void recordUserMessage(String userId, String sessionId, String message, String intentLabel);

    /**
     * 记录工具执行结果，自动提取环境事实、软件版本、失败信号等记忆。
     * <p>
     * 通过正则匹配从工具输出中提取：
     * <ul>
     *   <li>操作系统（ENVIRONMENT_FACT）</li>
     *   <li>软件版本（SOFTWARE_FACT，通用正则 + Redis 专用正则）</li>
     *   <li>失败信号（TROUBLESHOOTING_CASE，仅 success=false 时）</li>
     * </ul>
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success);

    /**
     * 记录助手回复，当包含"结论""原因""建议"等关键词时提取排查案例记忆（TROUBLESHOOTING_CASE）。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void recordAssistantConclusion(String userId, String sessionId, String assistantContent);

    /**
     * 保存用户消息并提取用户偏好记忆。
     * <p>
     * 将用户消息落库（role=user，仅首轮），同时调用 {@link #recordUserMessage} 检测偏好信号。
     * Case 层通过此方法完成"消息落库 + 长期记忆提取"的闭环，不再直接调用仓储层。
     *
     * @param userId      用户 ID
     * @param sessionId   会话 ID
     * @param message     用户原始消息
     * @param intentLabel 当前意图标签（可为 null）
     * @param firstRound  是否首轮（首轮才落库 user 消息，避免多轮循环重复写入）
     */
    void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound);

    /**
     * 保存助手回复并提取排查结论记忆。
     * <p>
     * 将助手回复落库（role=assistant），同时调用 {@link #recordAssistantConclusion} 检测结论信号。
     *
     * @param userId           用户 ID
     * @param sessionId        会话 ID
     * @param assistantContent 助手回复内容
     */
    void saveAssistantMessage(String userId, String sessionId, String assistantContent);

    /**
     * 保存工具执行结果并提取环境/软件/失败信号记忆。
     * <p>
     * 将工具结果落库（role=tool，priority=HIGH），同时调用 {@link #recordToolObservation} 提取记忆。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param toolName      工具名称
     * @param toolCallId    工具调用 ID
     * @param resultContent 工具执行结果内容
     * @param success       工具执行是否成功
     */
    void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success);

    /** 数据库业务文本只保存为安全会话历史，不据其内容推断跨会话环境事实。 */
    default void saveDatabaseToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent) {
        throw new UnsupportedOperationException("数据库工具历史持久化尚未实现");
    }

    /**
     * 按服务端认证后的归属主体加载会话历史，供 RootNode 冷启动恢复使用。
     */
    List<ChatMessageEntity> getRecentMessages(String userId, String sessionId, int limit);

    /**
     * 查询与当前对话相关的长期记忆（粗筛+精排两阶段召回）。
     * <p>
     * 阶段一：从 DB 拉最近 120 条记忆（粗筛）；
     * 阶段二：用查询关键词与记忆关键词做重叠打分（精排），返回 Top-N。
     *
     * @param userId 用户 ID
     * @param query  召回查询（首条+最近用户消息拼接）
     * @param limit  最大返回条数
     * @return 相关记忆列表（按打分降序）
     */
    List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit);

    /**
     * 构建长期记忆摘要字符串，用于注入 Prompt 的 [长期记忆] 段落。
     * <p>
     * 输出格式示例：
     * <pre>
     * - [USER_PREFERENCE] 以后执行命令都加 sudo
     * - [SOFTWARE_FACT] redis 版本为 7.0.11
     * </pre>
     *
     * @param userId 用户 ID
     * @param query  召回查询
     * @param limit  最大返回条数
     * @return 记忆摘要字符串，无记忆时返回空字符串
     */
    String buildMemorySummary(String userId, String query, int limit);
}
