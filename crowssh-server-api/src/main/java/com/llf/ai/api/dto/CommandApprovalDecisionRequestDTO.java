package com.llf.ai.api.dto;

import lombok.Data;

/**
 * AI SSH 命令审批决定。
 */
@Data
public class CommandApprovalDecisionRequestDTO {

    private String sessionId;
    private String decision;
}
