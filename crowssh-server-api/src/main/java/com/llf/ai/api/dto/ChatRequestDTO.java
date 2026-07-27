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
}
