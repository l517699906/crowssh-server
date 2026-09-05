package com.llf.ai.domain.agent.adapter.repository;

import com.llf.ai.domain.agent.model.entity.ChatMessageEntity;
import com.llf.ai.domain.agent.model.entity.ChatSessionEntity;
import com.llf.ai.domain.agent.model.valobj.prompt.MilestoneVO;

import java.util.List;

/**
 * 会话历史持久化仓储接口（领域层抽象）
 * <p>
 * 定义对话会话、消息、里程碑的持久化能力，由基础设施层 {@code ChatHistoryRepository} 实现。
 * 领域层通过此接口面向抽象编程，不依赖 MyBatis、PO 等基础设施细节。
 * <p>
 * 核心场景：
 * <ul>
 *   <li>{@link RootNode} 冷启动时通过 {@link #getRecentMessages} 加载历史消息恢复上下文</li>
 *   <li>{@link AiCallNode} / {@link ToolCallNode} 每轮将 user/assistant/tool 消息落库</li>
 *   <li>{@link MilestoneTracker} 检测到关键事件后通过 {@link #saveMilestone} 持久化</li>
 *   <li>前端历史记录功能通过 {@link #querySessionList} / {@link #queryMessageList} 获取数据</li>
 * </ul>
 *
 * @see com.llf.ai.infrastructure.adapter.repository.ChatHistoryRepository
 */
public interface IChatHistoryRepository {

    /**
     * 保存会话元数据（在 {@code ChatService.createSession} 创建 ADK Session 时同步落库）
     *
     * @param session 会话实体，包含 agentId、userId、title 等
     */
    void saveSession(ChatSessionEntity session);

    /**
     * 更新会话绑定的 SSH 资源，并返回实际匹配的会话行数。
     * <p>
     * 更新同时校验智能体和归属主体，避免仅凭 sessionId 修改其他主体的会话。
     * 返回值用于区分“更新成功”和“会话元数据尚未落库”，后者需要由调用方补写记录。
     *
     * @param agentId           智能体 ID
     * @param userId            服务端认证后的资源归属 ID
     * @param sessionId         会话 ID
     * @param connectionId      SSH 连接 ID，可为空
     * @param terminalSessionId SSH 终端会话 ID，可为空
     */
    int updateSessionBinding(String agentId, String userId, String sessionId,
                             String connectionId, String terminalSessionId);

    /**
     * 保存对话消息，同时更新会话表的 message_count 计数（+1）
     *
     * @param message 消息实体，包含 role、content、priority、tokenCount 等
     */
    void saveMessage(ChatMessageEntity message);

    /**
     * 获取指定归属主体的会话最近 N 条消息。
     * <p>通过 chat_session 归属条件与消息查询一起完成纵深隔离。</p>
     */
    List<ChatMessageEntity> getRecentMessages(String userId, String sessionId, int limit);

    /**
     * 获取指定 token 预算内的消息（从最近消息往前累积，直到预算耗尽）。
     * <p>
     * 与 2-6 节 SlidingWindowReducer 思路一致：最近的消息一定保留，最早的消息可能被丢弃。
     *
     * @param userId       服务端认证后的归属主体
     * @param sessionId    会话 ID
     * @param tokenBudget  token 预算上限
     * @return 预算内的消息列表（时间正序）
     */
    List<ChatMessageEntity> getMessagesWithBudget(String userId, String sessionId, int tokenBudget);

    /**
     * 保存里程碑事件（任务切换、错误、用户纠偏等关键事件）
     *
     * @param sessionId   会话 ID
     * @param milestoneVO 里程碑值对象
     */
    void saveMilestone(String sessionId, MilestoneVO milestoneVO);

    /**
     * 获取指定会话最近的里程碑事件
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回条数
     * @return 里程碑列表（时间倒序），无数据时返回空列表
     */
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);

    /**
     * 查询用户在某 Agent 下的会话列表（用于前端历史会话列表展示）
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数
     * @return 会话列表
     */
    List<ChatSessionEntity> querySessionList(String agentId, String userId, int limit);

    /**
     * 按会话 ID 和归属主体读取会话元数据。
     * <p>
     * 该查询用于服务重启后的冷启动恢复，归属条件必须在仓储查询中完成，
     * 不能只按客户端传入的 sessionId 判断。
     *
     * @param agentId   智能体 ID
     * @param userId    服务端认证后的资源归属 ID
     * @param sessionId 会话 ID
     * @return 属于指定主体的会话，不存在时返回 {@code null}
     */
    ChatSessionEntity findSession(String agentId, String userId, String sessionId);

    /**
     * 查询指定归属主体的会话消息列表。
     */
    List<ChatMessageEntity> queryMessageList(String userId, String sessionId, int limit);
}
