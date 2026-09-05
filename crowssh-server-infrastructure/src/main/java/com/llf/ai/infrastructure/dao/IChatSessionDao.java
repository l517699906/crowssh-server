package com.llf.ai.infrastructure.dao;

import com.llf.ai.infrastructure.dao.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话会话 MyBatis DAO 接口
 * <p>
 * 对应 chat_session 表，提供会话元数据插入、消息计数更新和会话列表查询能力。
 */
@Mapper
public interface IChatSessionDao {

    /**
     * 插入会话元数据记录
     *
     * @param po 会话持久化对象
     */
    void insert(ChatSessionPO po);

    /**
     * 按智能体、归属主体和会话 ID 更新 SSH 资源绑定。
     */
    int updateSessionBinding(@Param("agentId") String agentId,
                             @Param("userId") String userId,
                             @Param("sessionId") String sessionId,
                             @Param("connectionId") String connectionId,
                             @Param("terminalSessionId") String terminalSessionId);

    /**
     * 更新会话消息计数（message_count = message_count + 1，保证并发安全）
     *
     * @param sessionId 会话 ID
     */
    void updateMessageCount(@Param("sessionId") String sessionId);

    /**
     * 查询用户在某 Agent 下的会话列表（用于前端历史会话列表展示）
     *
     * @param agentId 智能体 ID
     * @param userId  用户 ID
     * @param limit   最大返回条数
     * @return 会话列表
     */
    List<ChatSessionPO> querySessionList(@Param("agentId") String agentId, @Param("userId") String userId, @Param("limit") int limit);

    /**
     * 按智能体、归属主体和会话 ID 查询单个会话元数据。
     */
    ChatSessionPO findSession(@Param("agentId") String agentId,
                              @Param("userId") String userId,
                              @Param("sessionId") String sessionId);
}
