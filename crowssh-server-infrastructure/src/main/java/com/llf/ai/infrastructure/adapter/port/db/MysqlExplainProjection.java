package com.llf.ai.infrastructure.adapter.port.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 计划仅保留结构和估算；条件、常量、消息和原始 JSON 均不离开此适配器。 */
final class MysqlExplainProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> FIELDS = List.of("table_name", "access_type", "key",
            "rows_examined_per_scan", "rows_produced_per_join", "filtered");

    static DbQueryResultEntity project(DbQueryResultEntity result, int limit) {
        if (result.getSafeError() != null) return result;
        var rows = new ArrayList<List<String>>();
        try {
            if (result.isTruncated() || result.getRows().size() != 1 || result.getRows().get(0).isEmpty()) {
                throw new IllegalArgumentException("计划不完整");
            }
            String raw = result.getRows().get(0).get(0);
            if (raw == null || raw.length() > 65536) throw new IllegalArgumentException("计划超限");
            var pending = new ArrayDeque<JsonNode>();
            pending.add(JSON.readTree(raw));
            int nodes = 0;
            while (!pending.isEmpty()) {
                if (++nodes > 4096) throw new IllegalArgumentException("计划过于复杂");
                JsonNode node = pending.removeFirst();
                if (node.isObject() && node.has("table_name")) {
                    if (rows.size() >= limit) { result.setTruncated(true); result.getTruncationReasons().add("PLAN_ROW_LIMIT"); break; }
                    var row = new ArrayList<String>();
                    for (int i = 0; i < FIELDS.size(); i++) {
                        JsonNode value = node.get(FIELDS.get(i));
                        String text = value == null || value.isNull() ? null : value.asText();
                        if (text != null && (text.length() > 128 || (i >= 3 && !text.matches("[0-9]+(?:\\.[0-9]+)?")))) text = null;
                        row.add(text);
                    }
                    rows.add(row);
                }
                if (node.isContainerNode()) node.elements().forEachRemaining(child -> {
                    if (child.isContainerNode()) pending.addLast(child);
                });
            }
        } catch (Exception ignored) {
            rows.clear();
            result.setSafeError("执行计划无法安全裁剪，原始内容已省略");
            result.setTruncated(true);
            result.getTruncationReasons().add("PLAN_OMITTED");
        }
        var columns = new ArrayList<DbQueryResultEntity.DbColumnEntity>();
        for (String field : FIELDS) columns.add(DbQueryResultEntity.DbColumnEntity.builder()
                .ordinal(columns.size() + 1).label(field.equals("key") ? "key_name" : field)
                .typeName("VARCHAR").jdbcType(java.sql.Types.VARCHAR).nullable(true).build());
        result.setColumns(columns); result.setRows(rows); result.setRowCount(rows.size());
        result.setResultBytes(rows.stream().flatMap(List::stream).filter(java.util.Objects::nonNull)
                .mapToLong(value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum());
        return result;
    }
    private MysqlExplainProjection() { }
}
