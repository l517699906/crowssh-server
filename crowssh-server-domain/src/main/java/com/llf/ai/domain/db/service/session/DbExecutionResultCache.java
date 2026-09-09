package com.llf.ai.domain.db.service.session;

import com.llf.ai.domain.db.config.DbSessionGovernanceProperties;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只回收负载，不删除执行身份；使用保守的内存估算而非驱动声称的字节数。 */
final class DbExecutionResultCache {
    private record Entry(String owner, DbQueryResultEntity result, long bytes) { }
    private final DbSessionGovernanceProperties.Execution settings;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> ownerBytes = new HashMap<>();
    private long totalBytes;

    DbExecutionResultCache(DbSessionGovernanceProperties.Execution settings) { this.settings = settings; }

    synchronized void put(String id, String owner, DbQueryResultEntity result) {
        remove(id);
        long bytes = estimate(result);
        entries.put(id, new Entry(owner, result, bytes));
        totalBytes += bytes;
        ownerBytes.merge(owner, bytes, Long::sum);
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (totalBytes > settings.getMaxRetainedResultBytesGlobal()
                    || ownerBytes.getOrDefault(entry.getValue().owner(), 0L) > settings.getMaxRetainedResultBytesPerOwner()) {
                subtract(entry.getValue());
                iterator.remove();
            }
        }
    }

    synchronized DbQueryResultEntity get(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.result();
    }

    synchronized void remove(String id) {
        Entry entry = entries.remove(id);
        if (entry != null) subtract(entry);
    }

    private void subtract(Entry entry) {
        totalBytes -= entry.bytes();
        ownerBytes.compute(entry.owner(), (owner, bytes) -> bytes == entry.bytes() ? null : bytes - entry.bytes());
    }

    private long estimate(DbQueryResultEntity result) {
        long bytes = 1024;
        for (var column : result.getColumns()) bytes += 128 + textBytes(column.getLabel()) + textBytes(column.getTypeName());
        for (var row : result.getRows()) {
            bytes += 32;
            for (String value : row) bytes += 8 + textBytes(value);
        }
        for (String value : result.getWarnings()) bytes += textBytes(value);
        for (String value : result.getTruncationReasons()) bytes += textBytes(value);
        return bytes + textBytes(result.getSafeError());
    }

    private long textBytes(String text) { return text == null ? 0 : 48 + 2L * text.length(); }

    static DbQueryResultEntity summary(DbQueryResultEntity result) {
        return DbQueryResultEntity.builder().rowCount(result.getRowCount())
                .affectedRows(result.getAffectedRows()).affectedRowsSemantics(result.getAffectedRowsSemantics())
                .truncated(result.isTruncated()).resultBytes(result.getResultBytes())
                .truncationReasons(java.util.List.copyOf(result.getTruncationReasons()))
                .sqlState(result.getSqlState()).vendorCode(result.getVendorCode()).safeError(result.getSafeError())
                .statementCancelled(result.isStatementCancelled())
                .queueMillis(result.getQueueMillis()).executionMillis(result.getExecutionMillis()).totalMillis(result.getTotalMillis())
                .autoCommit(result.isAutoCommit()).transactionState(result.getTransactionState())
                .currentDatabase(result.getCurrentDatabase()).sessionTimeZone(result.getSessionTimeZone())
                .sessionReusable(result.isSessionReusable()).capabilityState(result.getCapabilityState()).build();
    }
}
