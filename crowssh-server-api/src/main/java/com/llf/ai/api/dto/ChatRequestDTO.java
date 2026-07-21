package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class ChatRequestDTO {

    private String agentId;

    private String userId;

    private String sessionId;

    private String message;
}
