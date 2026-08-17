package com.llf.ai.domain.agent.service;

import com.google.adk.events.Event;
import com.llf.ai.domain.agent.model.entity.ChatCommandEntity;
import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

public interface IChatService {

    /**
     * 查询智能体配置列表
     */
    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    /**
     * 创建会话
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @return sessionId 会话ID
     */
    String createSession(String agentId, String userId);

    /**
     * 创建绑定到指定 SSH 连接的独立会话。
     */
    String createSession(String agentId, String userId, String connectionId, String terminalSessionId);

    /**
     * 恢复仍然有效的会话；如果服务端重启导致旧会话丢失，则创建新的会话。
     *
     * @param agentId             智能体 ID
     * @param userId              用户 ID
     * @param requestedSessionId  客户端历史记录中的会话 ID
     * @param connectionId        SSH 连接 ID
     * @param terminalSessionId   SSH 终端会话 ID
     * @return 可用于本次请求的会话 ID
     */
    String resolveSession(
            String agentId,
            String userId,
            String requestedSessionId,
            String connectionId,
            String terminalSessionId
    );

    /**
     * 处理消息
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param message 消息内容
     */
    List<String> handleMessage(String agentId, String userId, String message);

    /**
     * 处理消息（指定会话ID）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    /**
     * 处理消息（流式）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     * @return 事件流
     */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 处理消息（流式，兼容仅提供终端会话的调用方）。
     */
    Flowable<Event> handleMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String message,
            String terminalSessionId
    );

    /**
     * 处理消息（流式）
     * @param agentId 智能体ID
     * @param userId 用户ID
     * @param sessionId 会话ID
     * @param message 消息内容
     * @param terminalSessionId SSH终端会话ID（用于MCP工具调用）
     * @param connectionId SSH连接ID（用于校验终端归属）
     * @return 事件流
     */
    Flowable<Event> handleMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String message,
            String terminalSessionId,
            String connectionId
    );

    /**
     * 使用富化消息执行本轮模型调用，但在会话历史中保留未经富化的原始用户消息。
     */
    Flowable<Event> handleEnrichedMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String enrichedMessage,
            String originalMessage,
            String terminalSessionId,
            String connectionId
    );

    /**
     * 处理消息（流式）
     * @param chatCommandEntity 对话命令对象
     * @return 事件流
     */
    List<String> handleMessage(ChatCommandEntity chatCommandEntity);
}
