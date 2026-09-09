package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DbCancelResponseDTO {
    private String executionId;
    private boolean cancelRequested;
    private String state;
    private String outcome;
}
