package com.llf.ai.domain.db.model.valobj;

/** 内部固定查询契约；模型不能指定资源、任意诊断 SQL 或输出字段。 */
public record DbInspectionRequest(Operation operation, String database, String table, int limit, String query) {
    public enum Operation { DATABASES, TABLES, COLUMNS, INDEXES, INSTANCE, PROCESSES, LOCKS, EXPLAIN }

    public DbInspectionRequest {
        if (operation == null) throw new IllegalArgumentException("诊断操作不能为空");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("诊断行数必须在 1 到 1000 之间");
        if (operation == Operation.TABLES || operation == Operation.COLUMNS || operation == Operation.INDEXES) {
            identifier(database, "数据库");
        }
        if (operation == Operation.COLUMNS || operation == Operation.INDEXES) identifier(table, "表");
        if (operation == Operation.EXPLAIN) {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("待解释查询不能为空");
        } else if (query != null) throw new IllegalArgumentException("结构化诊断不接受 SQL");
    }

    private static void identifier(String value, String field) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException(field + "标识符不合法");
        }
    }
}
