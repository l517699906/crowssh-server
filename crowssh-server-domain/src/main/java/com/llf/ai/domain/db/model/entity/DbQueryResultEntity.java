package com.llf.ai.domain.db.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DbQueryResultEntity {
    /** 驱动明确报告语句取消；不等同于事务已回滚，保留原始厂商错误码。 */
    private boolean statementCancelled;
    @Builder.Default
    private List<DbColumnEntity> columns = new ArrayList<>();
    @Builder.Default
    private List<List<String>> rows = new ArrayList<>();
    private long rowCount;
    private Long affectedRows;
    private String affectedRowsSemantics;
    private boolean truncated;
    @Builder.Default
    private List<String> truncationReasons = new ArrayList<>();
    private long resultBytes;
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
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
    private com.llf.ai.domain.db.model.valobj.DbCapabilityState capabilityState;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DbColumnEntity {
        private int ordinal;
        private String label;
        private String typeName;
        private int jdbcType;
        private boolean nullable;
        private Integer precision;
        private Integer scale;
    }
}
