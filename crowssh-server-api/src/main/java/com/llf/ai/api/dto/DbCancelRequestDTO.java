package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbCancelRequestDTO {
    private String dbSessionId;
    private String executionId;
}
