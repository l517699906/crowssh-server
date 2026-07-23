package com.llf.ai.domain.ssh.model.entity;

import com.llf.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.llf.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH连接配置实体
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionEntity {

    private Long id;
    private String connectionId;          // 业务主键（UUID）
    private String connectionName;        // 连接名称
    private String host;                  // 主机地址
    private Integer port;                 // 端口号
    private String username;              // 用户名
    private AuthTypeEnum authType;        // 认证类型（值对象）
    private String password;              // 密码
    private String privateKey;            // 私钥
    private Integer encrypted;            // 是否加密
    private ConnectionStatusEnum status;  // 连接状态（值对象）
    private String userId;                // 用户ID
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 校验必填字段
     */
    public void validate() {
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalArgumentException("连接名称不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("主机地址不能为空");
        }
        if (port == null || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口号不合法");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
    }
}