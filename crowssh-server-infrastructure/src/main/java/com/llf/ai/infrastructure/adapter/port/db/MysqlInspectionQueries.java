package com.llf.ai.infrastructure.adapter.port.db;

import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;
import java.util.List;

/** 审核过的只读模板；结构参数绑定，不读取注释、默认值、SQL 文本或锁数据。 */
final class MysqlInspectionQueries {
    record Query(String sql, List<String> parameters) { }

    static Query create(DbInspectionRequest request) {
        return switch (request.operation()) {
            case DATABASES -> new Query("SELECT SCHEMA_NAME AS database_name FROM information_schema.SCHEMATA "
                    + "WHERE SCHEMA_NAME NOT IN ('information_schema','performance_schema','mysql','sys') ORDER BY SCHEMA_NAME", List.of());
            case TABLES -> new Query("SELECT TABLE_NAME AS table_name, TABLE_TYPE AS table_type, ENGINE AS engine, "
                    + "TABLE_ROWS AS estimated_rows, COALESCE(DATA_LENGTH,0)+COALESCE(INDEX_LENGTH,0) AS size_bytes "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME", List.of(request.database()));
            case COLUMNS -> new Query("SELECT c.ORDINAL_POSITION AS ordinal_position, c.COLUMN_NAME AS column_name, "
                    + "c.DATA_TYPE AS data_type, c.IS_NULLABLE AS is_nullable, c.COLUMN_KEY AS column_key, "
                    + "s.INDEX_NAME AS index_name, s.SEQ_IN_INDEX AS index_position, s.NON_UNIQUE AS non_unique, "
                    + "s.INDEX_TYPE AS index_type FROM information_schema.COLUMNS c LEFT JOIN information_schema.STATISTICS s "
                    + "ON s.TABLE_SCHEMA=c.TABLE_SCHEMA AND s.TABLE_NAME=c.TABLE_NAME AND s.COLUMN_NAME=c.COLUMN_NAME "
                    + "WHERE c.TABLE_SCHEMA=? AND c.TABLE_NAME=? ORDER BY c.ORDINAL_POSITION,s.INDEX_NAME,s.SEQ_IN_INDEX",
                    List.of(request.database(), request.table()));
            case INDEXES -> new Query("SELECT INDEX_NAME AS index_name, NON_UNIQUE AS non_unique, SEQ_IN_INDEX AS seq_in_index, "
                    + "COLUMN_NAME AS column_name, INDEX_TYPE AS index_type FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY INDEX_NAME,SEQ_IN_INDEX", List.of(request.database(), request.table()));
            case INSTANCE -> new Query("SELECT 'version' AS metric, VERSION() AS value UNION ALL "
                    + "SELECT VARIABLE_NAME AS metric, VARIABLE_VALUE AS value FROM performance_schema.global_status "
                    + "WHERE VARIABLE_NAME IN ('Uptime','Threads_connected','Threads_running','Connections',"
                    + "'Slow_queries','Questions','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads',"
                    + "'Innodb_buffer_pool_pages_total','Innodb_buffer_pool_pages_free')", List.of());
            case PROCESSES -> new Query("SELECT ID AS process_id, DB AS database_name, COMMAND AS command, "
                    + "TIME AS elapsed_seconds, STATE AS state FROM performance_schema.processlist ORDER BY TIME DESC,ID", List.of());
            case LOCKS -> new Query("SELECT REQUESTING_ENGINE_TRANSACTION_ID AS requesting_transaction_id, "
                    + "BLOCKING_ENGINE_TRANSACTION_ID AS blocking_transaction_id, REQUESTING_THREAD_ID AS requesting_thread_id, "
                    + "BLOCKING_THREAD_ID AS blocking_thread_id FROM performance_schema.data_lock_waits", List.of());
            // 原始查询必须先经过领域层与业务 SELECT 相同的语法和列授权检查。
            case EXPLAIN -> new Query("EXPLAIN FORMAT=JSON " + request.query(), List.of());
        };
    }

    private MysqlInspectionQueries() { }
}
