package com.llf.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DbConnectionRequestDTO {
    private String connectionId;
    private Long expectedConfigVersion;
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
    private String aiDataMode;
    private List<DbAllowedColumnDTO> aiAllowedColumns;
    private String passwordAction;
    @lombok.ToString.Exclude
    private String password;
    private String caCertificateAction;
    private String caCertificatePem;
}
