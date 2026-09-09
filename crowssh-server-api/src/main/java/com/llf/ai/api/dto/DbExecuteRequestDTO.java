package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbExecuteRequestDTO {
    private String dbSessionId;
    private String executionId;
}
