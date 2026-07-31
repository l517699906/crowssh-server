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
     * 客户端临时提供的模型配置，仅在本次请求期间使用，不做持久化。
     */
    private RuntimeModelConfigDTO runtimeModel;

    public void clearRuntimeSecret() {
        if (runtimeModel != null) {
            runtimeModel.setApiKey(null);
        }
    }
}
