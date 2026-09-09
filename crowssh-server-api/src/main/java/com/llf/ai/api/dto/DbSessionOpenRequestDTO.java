package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbSessionOpenRequestDTO {
    private String connectionId;
    private String dbSessionId;
}
