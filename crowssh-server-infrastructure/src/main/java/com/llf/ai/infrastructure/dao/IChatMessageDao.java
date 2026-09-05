package com.llf.ai.infrastructure.dao;

import com.llf.ai.infrastructure.dao.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对话消息 MyBatis DAO 接口
 * <p>
 * 对应 chat_message 表，提供消息插入和按会话查询最近消息的能力。
 */
@Mapper
public interface IChatMessageDao {

    /**
     * 插入一条对话消息
     *
     * @param po 消息持久化对象
     */
    void insert(ChatMessagePO po);

    /**
     * 按归属主体和会话 ID 查询最近消息，避免仅凭客户端 sessionId 读取其他主体历史。
     */
    List<ChatMessagePO> queryRecentByOwnerAndSessionId(@Param("userId") String userId,
                                                       @Param("sessionId") String sessionId,
                                                       @Param("limit") int limit);
}
