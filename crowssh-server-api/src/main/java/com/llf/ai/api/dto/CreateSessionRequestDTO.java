package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class CreateSessionRequestDTO {

    private String agentId;

    private String connectionId;

    private String terminalSessionId;
    private String dbConnectionId;
    private String dbSessionId;
}
