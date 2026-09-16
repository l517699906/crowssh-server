package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DbPrepareResponseDTO {
    private String executionId;
    private String state;
    private String outcome;
    private String kind;
    private String riskLevel;
    private List<String> reasons;
    private String sqlHash;
    private String targetDatabase;
    private long configVersion;
    private long targetContextVersion;
    private LocalDateTime expiresAt;
}
