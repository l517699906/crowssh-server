package com.llf.ai.api.dto;

import lombok.Data;

@Data
public class DbAllowedColumnDTO {
    private String database;
    private String table;
    private String column;
}
