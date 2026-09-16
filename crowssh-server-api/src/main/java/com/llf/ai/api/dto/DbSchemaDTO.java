package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DbSchemaDTO {
    private String database;
    private String name;
    private String kind;
    private String engine;
    private Long estimatedRows;
    private Long sizeBytes;
    private List<ColumnDTO> columns;

    @Data
    @Builder
    public static class ColumnDTO {
        private int ordinal;
        private String name;
        private String typeName;
        private boolean nullable;
        private String keyType;
        private String indexName;
        private List<IndexMemberDTO> indexes;
    }

    public record IndexMemberDTO(String name, int position, boolean unique, String type) {
    }
}
