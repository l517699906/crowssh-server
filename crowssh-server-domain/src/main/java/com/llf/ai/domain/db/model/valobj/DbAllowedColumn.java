package com.llf.ai.domain.db.model.valobj;

import java.util.Objects;

/** AI 可访问的精确 database/table/column 规则，不支持通配符。 */
public record DbAllowedColumn(String database, String table, String column) {

    public DbAllowedColumn {
        database = requireIdentifier(database, "database");
        table = requireIdentifier(table, "table");
        column = requireIdentifier(column, "column");
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || "*".equals(value)
                || !value.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException(field + " 必须是精确的 MySQL 标识符");
        }
        return value;
    }

    public boolean matches(String databaseName, String tableName, String columnName) {
        return Objects.equals(database, databaseName)
                && Objects.equals(table, tableName)
                && Objects.equals(column, columnName);
    }
}
