package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DbConnectionResponseDTO {
    private String connectionId;
    private String connectionName;
    private String dbType;
    private String host;
    private Integer port;
    private String username;
    private String defaultDatabase;
    private String sslMode;
    private String tlsServerName;
    private String tunnelSshConnectionId;
    private Integer connectTimeout;
    private Integer queryTimeout;
    private Integer maxRows;
    private Long configVersion;
    private String aiDataMode;
    private List<DbAllowedColumnDTO> aiAllowedColumns;
    private boolean hasPassword;
    private boolean hasCaCertificate;
    private Integer status;
    private String createdAt;
    private String updatedAt;
}
