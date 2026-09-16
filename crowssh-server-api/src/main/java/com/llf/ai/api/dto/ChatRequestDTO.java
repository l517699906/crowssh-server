package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;

    private String userId;

    private String sessionId;

    private String message;

    /**
     * SSH 终端会话 ID（用于智能体执行命令）
     * 如果未指定，系统将尝试从会话绑定中获取
     */
    private String terminalSessionId;

    /**
     * SSH 连接 ID，用于校验终端会话确实属于当前服务器。
     */
    private String connectionId;
    private String dbConnectionId;
    private String dbSessionId;
    /** 客户端明确声明能展示完整数据库审批卡片。 */
    private boolean supportsDbApproval;

    public boolean isDatabaseRequest() {
        return dbConnectionId != null || dbSessionId != null;
    }

    public void validateResourceBinding() {
        if (isDatabaseRequest() && (dbConnectionId == null || dbConnectionId.isBlank()
                || dbSessionId == null || dbSessionId.isBlank()
                || connectionId != null || terminalSessionId != null)) {
            throw new IllegalArgumentException("数据库资源绑定必须完整且不能混合 SSH 资源");
        }
    }

    /**
     * 客户端临时提供的模型配置，仅在本次请求期间使用，不做持久化。
     */
    private RuntimeModelConfigDTO runtimeModel;

    public void clearRuntimeSecret() {
        if (runtimeModel != null) {
            runtimeModel.setApiKey(null);
        }
    }
}
