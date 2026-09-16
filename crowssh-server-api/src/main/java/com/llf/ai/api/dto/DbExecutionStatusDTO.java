package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DbExecutionStatusDTO {
    private String executionId;
    private String source;
    private String operationKind;
    private String kind;
    private String state;
    private String outcome;
    private boolean success;
    private boolean cancelRequested;
    private boolean resultAvailable;
    private String targetDatabase;
    private long configVersion;
    private String riskLevel;
    private String policyReason;
    private String startedAt;
    private String finishedAt;
    private DbQueryResultDTO result;
}
