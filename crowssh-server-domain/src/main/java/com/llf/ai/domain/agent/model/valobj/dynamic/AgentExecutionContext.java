package com.llf.ai.domain.agent.model.valobj.dynamic;

import lombok.Builder;
import lombok.Value;

/**
 * 子 Agent 执行上下文 - 贯穿一次动态派发全流程的上下文信息。
 * <p>
 * 由派发工具（DynamicPlanDispatchTool / BatchSubAgentDispatchTool）从
 * ToolContext 中提取构建，随任务传递给编排器与派发服务，
 * 用于在子 Agent 会话中复用父会话绑定的 SSH 终端会话。
 */
@Value
@Builder
public class AgentExecutionContext {

    /**
     * 发起对话的用户 ID
     */
    String userId;

    /**
     * 发起派发的父 Agent ID（即 ADK 中的 agentName）
     */
    String agentId;

    /**
     * 父 Agent 名称
     */
    String parentAgentName;

    /**
     * ADK 业务会话 ID，用于在父 invocation 事件尚未到达 SSE 时兜底关联事件。
     */
    String parentSessionKey;

    /**
     * 父 Agent 会话 ID
     */
    String parentSessionId;

    /**
     * 父会话绑定的 SSH 终端会话 ID，派发时透传给 SshExecuteAdkTool 的 ThreadLocal
     */
    String terminalSessionId;

    /**
     * 当前的连接 ID
     */
    String connectionId;

    /**
     * 当前执行的任务 ID（预留）
     */
    String taskId;
}
