package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    private String agentId;

    private String userId;

    private String connectionId;

    private String terminalSessionId;
}
