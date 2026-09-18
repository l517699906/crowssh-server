package com.llf.ai.domain.agent.model.valobj.dynamic;

import lombok.Builder;
import lombok.Value;

/** 服务端捕获的资源快照；不得从模型参数或子任务文本构造身份。 */
@Value
@Builder
public class AgentExecutionContext {
    String userId;
    /** 业务 Agent ID。 */
    String agentId;
    String parentAgentName;
    /** 根聊天会话，用于审批与事件路由，不是 invocationId。 */
    String parentSessionId;
    String terminalSessionId;
    String connectionId;
    String taskId;
}
