package com.llf.ai.domain.db.model.entity;

import com.llf.ai.domain.db.model.valobj.AiDataModeEnum;
import com.llf.ai.domain.db.model.valobj.DbAllowedColumn;
import com.llf.ai.domain.db.model.valobj.DbTypeEnum;
import com.llf.ai.domain.db.model.valobj.SslModeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class DbConnectionEntity {

    private Long id;
    private String connectionId;
    private String connectionName;
    private DbTypeEnum dbType;
    private String host;
    private Integer port;
    private String username;
    /** 仅在建连入口短暂存在；不进入 DTO、事件或日志。 */
    @lombok.ToString.Exclude
    private String password;
    @lombok.ToString.Exclude
    private String passwordCiphertext;
    /** 仅表示服务端是否保存过密码，不承载密码值。 */
    private boolean passwordPresent;
    private String defaultDatabase;
    private SslModeEnum sslMode;
    private String tlsServerName;
    /** 公共 CA 证书链，不是私钥。 */
    private String caCertificatePem;
    private String tunnelSshConnectionId;
    private Integer connectTimeout;
    private Integer queryTimeout;
    private Integer maxRows;
    private Long configVersion;
    private AiDataModeEnum aiDataMode;
    @Builder.Default
    private List<DbAllowedColumn> aiAllowedColumns = new ArrayList<>();
    private Integer status;
    private String userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void applyDefaults() {
        if (dbType == null) dbType = DbTypeEnum.MYSQL;
        if (port == null) port = 3306;
        if (sslMode == null) sslMode = SslModeEnum.VERIFY_IDENTITY;
        if (connectTimeout == null) connectTimeout = 10;
        if (queryTimeout == null) queryTimeout = 60;
        if (maxRows == null) maxRows = 1000;
        if (configVersion == null) configVersion = 1L;
        if (aiDataMode == null) aiDataMode = AiDataModeEnum.METADATA_ONLY;
        if (aiAllowedColumns == null) aiAllowedColumns = new ArrayList<>();
    }

    public void validate() {
        applyDefaults();
        requireLength(connectionName, "连接名称", 128);
        requireLength(host, "主机地址", 255);
        requireLength(username, "用户名", 128);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口号不合法");
        if (containsJdbcControlCharacter(host)) throw new IllegalArgumentException("主机地址包含非法字符");
        if (containsJdbcControlCharacter(defaultDatabase)) throw new IllegalArgumentException("默认数据库包含非法字符");
        if (defaultDatabase != null && !defaultDatabase.isBlank()
                && !defaultDatabase.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException("默认数据库标识符不合法");
        }
        if (connectTimeout < 1 || connectTimeout > 30) throw new IllegalArgumentException("连接超时必须在 1～30 秒");
        if (queryTimeout < 1 || queryTimeout > 120) throw new IllegalArgumentException("查询超时必须在 1～120 秒");
        if (maxRows < 1 || maxRows > 5000) throw new IllegalArgumentException("最大行数必须在 1～5000");
        if (tlsServerName != null && (tlsServerName.isBlank() || tlsServerName.length() > 255
                || containsJdbcControlCharacter(tlsServerName))) {
            throw new IllegalArgumentException("TLS 证书身份不合法");
        }
        if (caCertificatePem != null) {
            if (caCertificatePem.length() > 256 * 1024
                    || caCertificatePem.contains("PRIVATE KEY")
                    || !caCertificatePem.contains("BEGIN CERTIFICATE")) {
                throw new IllegalArgumentException("CA 必须是限长的公共证书链");
            }
        }
        if (tunnelSshConnectionId != null && !tunnelSshConnectionId.isBlank()
                && tunnelSshConnectionId.length() > 64) {
            throw new IllegalArgumentException("SSH 隧道连接 ID 不合法");
        }
        if (aiDataMode == AiDataModeEnum.METADATA_ONLY && !aiAllowedColumns.isEmpty()) {
            throw new IllegalArgumentException("仅元数据模式不能配置业务列白名单");
        }
    }

    public boolean hasPassword() {
        return passwordPresent || (password != null && !password.isEmpty());
    }

    public boolean hasCaCertificate() {
        return caCertificatePem != null && !caCertificatePem.isBlank();
    }

    private static void requireLength(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        if (value.length() > max) throw new IllegalArgumentException(field + "超出长度限制");
    }

    private static boolean containsJdbcControlCharacter(String value) {
        return value != null && (value.indexOf('\0') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('&') >= 0 || value.indexOf('#') >= 0
                || value.indexOf('=') >= 0 || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0 || value.indexOf('@') >= 0
                || value.chars().anyMatch(Character::isWhitespace));
    }
}
