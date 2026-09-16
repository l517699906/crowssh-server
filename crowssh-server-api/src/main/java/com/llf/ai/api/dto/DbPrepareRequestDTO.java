package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbPrepareRequestDTO {
    private String dbSessionId;
    private String sql;
    private Long expectedTargetContextVersion;
}
