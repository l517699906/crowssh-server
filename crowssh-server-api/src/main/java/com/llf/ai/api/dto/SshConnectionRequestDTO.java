package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH连接请求DTO
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SshConnectionRequestDTO {

    /** 连接ID（更新时必传） */
    private String connectionId;

    /** 连接名称 */
    private String connectionName;

    /** 主机地址 */
    private String host;

    /** 端口号 */
    private Integer port;

    /** 用户名 */
    private String username;

    /** 认证类型: 1-密码, 2-私钥 */
    private Integer authType;

    /** 密码 */
    private String password;

    /** 私钥内容 */
    private String privateKey;

    /** 用户ID */
    private String userId;

    // ---------- 高级配置 ----------

    /** 连接超时时间(秒) */
    private Integer connectTimeout;

    /** 保活间隔(秒) */
    private Integer keepaliveInterval;

    /** 启动命令 */
    private String startupCommand;

    /** 是否压缩 */
    private Boolean compression;

    /** 严格主机密钥检查 */
    private Boolean strictHostKeyCheck;
}
