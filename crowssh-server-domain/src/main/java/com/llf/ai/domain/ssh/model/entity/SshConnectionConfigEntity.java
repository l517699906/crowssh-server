package com.llf.ai.domain.ssh.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * SSH连接高级配置实体
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionConfigEntity {

    private static final Pattern SHA256_FINGERPRINT = Pattern.compile(
            "^SHA256:[A-Za-z0-9+/]{43}=?$");

    private Long id;
    private String connectionId;          // 关联的连接ID
    private Integer connectTimeout;       // 连接超时（秒）
    private Integer keepaliveInterval;    // 保活间隔（秒）
    private String startupCommand;        // 启动命令
    private Boolean compression;          // 是否压缩
    private Boolean strictHostKeyCheck;   // 严格主机密钥检查
    private String knownHosts;            // 已知主机密钥
    private LocalDateTime updatedAt;

    /**
     * 设置默认值
     */
    public SshConnectionConfigEntity withDefaults() {
        if (connectTimeout == null) connectTimeout = 30;
        if (keepaliveInterval == null) keepaliveInterval = 60;
        if (compression == null) compression = false;
        // 主机密钥校验是服务端安全边界，旧配置中的 false 也必须迁移为开启。
        strictHostKeyCheck = true;
        return this;
    }

    /**
     * 校验运行时配置
     */
    public void validate() {
        if (connectTimeout == null || connectTimeout < 1 || connectTimeout > 120) {
            throw new IllegalArgumentException("连接超时时间必须在 1 到 120 秒之间");
        }
        if (keepaliveInterval == null || keepaliveInterval < 0 || keepaliveInterval > 300) {
            throw new IllegalArgumentException("保活间隔必须在 0 到 300 秒之间");
        }
        if (knownHosts != null && !knownHosts.isBlank()
                && !SHA256_FINGERPRINT.matcher(knownHosts.trim()).matches()) {
            throw new IllegalArgumentException("SSH 主机密钥指纹格式不合法");
        }
    }
}
