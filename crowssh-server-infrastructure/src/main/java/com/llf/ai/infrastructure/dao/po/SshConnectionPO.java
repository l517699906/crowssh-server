package com.llf.ai.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SSH连接配置持久化对象
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ssh_connection")
public class SshConnectionPO {

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 连接唯一标识(UUID) */
    private String connectionId;
    /** 连接名称 */
    private String connectionName;
    /** 主机地址 */
    private String host;
    /** 端口号 */
    private Integer port;
    /** 用户名 */
    private String username;
    /** 认证类型:1-密码,2-私钥 */
    private Integer authType;
    /** 密码(加密存储) */
    private String password;
    /** 私钥内容(加密存储) */
    private String privateKey;
    /** 是否加密:0-否,1-是 */
    private Integer encrypted;
    /** 连接状态:0-未连接,1-已连接,2-连接中,3-连接失败 */
    private Integer status;
    /** 用户ID */
    private String userId;
    /** 创建时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    /** 更新时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
    /** 逻辑删除:0-未删除,1-已删除 */
    @TableLogic(value = "0", delval = "1")
    @TableField(insertStrategy = FieldStrategy.NEVER)
    private Integer deleted;
}
