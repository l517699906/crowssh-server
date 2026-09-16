package com.llf.ai.domain.db.service.output;

import com.llf.ai.domain.db.adapter.port.ISqlAnalysisPort;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 仅安全副本可进入模型、事件和历史；预算包含 JSON 转义开销的保守上界。 */
public class DbAiOutputPolicy {
    private static final List<String> SENSITIVE_NAMES = List.of(
            "password", "passwd", "token", "secret", "credential", "phone", "id_card");

    public DbQueryResultEntity sanitize(DbQueryResultEntity source, int maxRows, int maxCellBytes, int maxResultBytes) {
        return sanitize(source, null, maxRows, maxCellBytes, maxResultBytes);
    }

    public DbQueryResultEntity sanitize(DbQueryResultEntity source, ISqlAnalysisPort.Analysis analysis,
                                        int maxRows, int maxCellBytes, int maxResultBytes) {
        boolean maskSources = analysis == null || analysis.hasUnresolvedReferences()
                || analysis.references().stream().anyMatch(ref -> isSensitive(ref.column()));
        return boundedCopy(source, maskSources, maxRows, maxCellBytes, maxResultBytes);
    }

    /** 固定诊断列单独授权，原始 SQL、注释、默认值、锁内容和执行计划条件不进入模型。 */
    public DbQueryResultEntity sanitizeMetadata(DbQueryResultEntity source, DbInspectionRequest.Operation operation,
                                                int maxRows, int maxCellBytes, int maxResultBytes) {
        if (source == null) return null;
        var allowed = switch (operation) {
            case DATABASES -> List.of("database_name");
            case TABLES -> List.of("table_name", "table_type", "engine", "estimated_rows", "size_bytes");
            case COLUMNS -> List.of("ordinal_position", "column_name", "data_type", "is_nullable", "column_key",
                    "index_name", "index_position", "non_unique", "index_type");
            case INDEXES -> List.of("index_name", "non_unique", "seq_in_index", "column_name", "index_type");
            case INSTANCE -> List.of("metric", "value");
            case PROCESSES -> List.of("process_id", "database_name", "command", "elapsed_seconds", "state");
            case LOCKS -> List.of("requesting_transaction_id", "blocking_transaction_id", "requesting_thread_id", "blocking_thread_id");
            case EXPLAIN -> List.of("table_name", "access_type", "key_name", "rows_examined_per_scan", "rows_produced_per_join", "filtered");
        };
        var filtered = DbQueryResultEntity.builder()
                .capabilityState(source.getCapabilityState()).statementCancelled(source.isStatementCancelled())
                .safeError(source.getSafeError()).sqlState(source.getSqlState()).vendorCode(source.getVendorCode())
                .autoCommit(source.isAutoCommit()).transactionState(source.getTransactionState())
                .sessionReusable(source.isSessionReusable()).executionMillis(source.getExecutionMillis())
                .totalMillis(source.getTotalMillis()).truncated(source.isTruncated()).build();
        var positions = new ArrayList<Integer>();
        var original = source.getColumns() == null ? List.<DbQueryResultEntity.DbColumnEntity>of() : source.getColumns();
        for (int i = 0; i < original.size(); i++) {
            String label = original.get(i).getLabel();
            if (label != null && allowed.contains(label.toLowerCase(Locale.ROOT))) {
                positions.add(i); filtered.getColumns().add(original.get(i));
            }
        }
        for (var row : source.getRows() == null ? List.<List<String>>of() : source.getRows()) {
            var values = new ArrayList<String>();
            for (int index : positions) values.add(index < row.size() ? row.get(index) : null);
            filtered.getRows().add(values);
        }
        filtered.setTruncated(source.isTruncated() || positions.size() != original.size());
        return boundedCopy(filtered, false, maxRows, maxCellBytes, maxResultBytes);
    }

    private DbQueryResultEntity boundedCopy(DbQueryResultEntity source, boolean maskSources,
                                             int maxRows, int maxCellBytes, int maxResultBytes) {
        if (source == null) return null;
        int rowLimit = Math.max(0, Math.min(50, maxRows));
        int cellLimit = Math.max(0, Math.min(2048, maxCellBytes));
        // 无可靠 tokenizer 时，一字节至少占一个预算单位；同时预留信封和状态字段。
        long budget = Math.max(0, Math.min(6000, maxResultBytes) - 1024);
        List<DbQueryResultEntity.DbColumnEntity> columns = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (source.isTruncated()) reasons.add("SOURCE_TRUNCATED");
        List<DbQueryResultEntity.DbColumnEntity> original = source.getColumns() == null ? List.of() : source.getColumns();
        long bytes = 1024;
        for (var column : original) {
            if (columns.size() >= 64 || budget < 160) { reasons.add("AI_COLUMN_LIMIT"); break; }
            String label = safeIdentifier(column.getLabel(), "column_" + (columns.size() + 1));
            String type = safeIdentifier(column.getTypeName(), "VALUE");
            long cost = 160 + 6L * (label.length() + type.length());
            if (cost > budget) { reasons.add("AI_RESULT_LIMIT"); break; }
            budget -= cost; bytes += cost;
            columns.add(DbQueryResultEntity.DbColumnEntity.builder().ordinal(columns.size() + 1)
                    .label(label).typeName(type).jdbcType(column.getJdbcType()).nullable(column.isNullable())
                    .precision(column.getPrecision()).scale(column.getScale()).build());
        }
        for (List<String> row : source.getRows() == null ? List.<List<String>>of() : source.getRows()) {
            if (rows.size() >= rowLimit || columns.isEmpty()) { add(reasons, "AI_ROW_LIMIT"); break; }
            List<String> safeRow = new ArrayList<>();
            long cost = 2;
            for (int i = 0; i < columns.size(); i++) {
                String value = i < row.size() ? row.get(i) : null;
                if (value != null && (maskSources || isSensitive(original.get(i).getLabel()))) {
                    value = "[MASKED]"; add(reasons, "SENSITIVE_SOURCE_MASKED");
                }
                if (value != null) {
                    String bounded = truncateUtf8(value, cellLimit);
                    if (!bounded.equals(value)) add(reasons, "AI_CELL_LIMIT");
                    value = bounded;
                }
                cost += value == null ? 5 : 3 + 6L * value.getBytes(StandardCharsets.UTF_8).length;
                safeRow.add(value);
            }
            if (cost > budget) { add(reasons, "AI_RESULT_LIMIT"); break; }
            budget -= cost; bytes += cost; rows.add(safeRow);
        }
        return DbQueryResultEntity.builder().columns(columns).rows(rows).rowCount(rows.size())
                .capabilityState(source.getCapabilityState()).statementCancelled(source.isStatementCancelled())
                .affectedRows(source.getAffectedRows()).affectedRowsSemantics(source.getAffectedRowsSemantics())
                .truncated(!reasons.isEmpty()).truncationReasons(reasons).resultBytes(bytes)
                .warnings(List.of()).sqlState(source.getSqlState()).vendorCode(source.getVendorCode())
                .safeError(source.getSafeError() == null ? null : "数据库操作失败，详情已省略，请根据错误码核查")
                .autoCommit(source.isAutoCommit()).transactionState(source.getTransactionState())
                .executionMillis(source.getExecutionMillis()).totalMillis(source.getTotalMillis())
                .sessionReusable(source.isSessionReusable()).build();
    }

    private String safeIdentifier(String text, String fallback) {
        return text != null && text.matches("[A-Za-z_][A-Za-z0-9_ ]{0,63}") ? text : fallback;
    }
    private static void add(List<String> values, String value) { if (!values.contains(value)) values.add(value); }
    private boolean isSensitive(String name) {
        return name != null && SENSITIVE_NAMES.stream().anyMatch(name.toLowerCase(Locale.ROOT)::contains);
    }
    private String truncateUtf8(String text, int limit) {
        int offset = 0, bytes = 0;
        while (offset < text.length()) {
            int codepoint = text.codePointAt(offset);
            int size = codepoint <= 0x7f ? 1 : codepoint <= 0x7ff ? 2 : codepoint <= 0xffff ? 3 : 4;
            if (bytes + size > limit) break;
            bytes += size; offset += Character.charCount(codepoint);
        }
        return text.substring(0, offset);
    }
}
