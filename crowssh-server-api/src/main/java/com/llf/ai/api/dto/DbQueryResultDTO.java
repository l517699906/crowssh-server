package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DbQueryResultDTO {
    private List<ColumnDTO> columns;
    private List<List<String>> rows;
    private long rowCount;
    private String affectedRows;
    private String affectedRowsSemantics;
    private boolean truncated;
    private List<String> truncationReasons;
    private long resultBytes;
    private List<String> warnings;
    private String sqlState;
    private Integer vendorCode;
    private String safeError;
    private long queueMillis;
    private long executionMillis;
    private long totalMillis;
    private boolean autoCommit;
    private String transactionState;
    private String currentDatabase;
    private String sessionTimeZone;
    private boolean sessionReusable;
    private String capabilityState;

    @Data
    @Builder
    public static class ColumnDTO {
        private int ordinal;
        private String label;
        private String typeName;
        private int jdbcType;
        private boolean nullable;
        private Integer precision;
        private Integer scale;
    }
}
