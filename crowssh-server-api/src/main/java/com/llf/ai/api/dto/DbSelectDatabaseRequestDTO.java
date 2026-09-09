package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbSelectDatabaseRequestDTO {
    private String dbSessionId;
    private String database;
    private Long expectedTargetContextVersion;
}
