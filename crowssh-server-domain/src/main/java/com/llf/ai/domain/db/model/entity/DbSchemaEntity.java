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
public class DbSchemaEntity {
    private String database;
    private String name;
    private String kind;
    private String engine;
    private Long estimatedRows;
    private Long sizeBytes;
    @Builder.Default
    private List<Column> columns = new ArrayList<>();

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Column {
        private int ordinal;
        private String name;
        private String typeName;
        private boolean nullable;
        private String keyType;
        private String indexName;
        @Builder.Default
        private List<IndexMember> indexes = new ArrayList<>();
    }

    public record IndexMember(String name, int position, boolean unique, String type) {
    }
}
