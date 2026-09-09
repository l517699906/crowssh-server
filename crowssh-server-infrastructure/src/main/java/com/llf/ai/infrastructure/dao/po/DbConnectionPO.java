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

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("db_connection")
public class DbConnectionPO {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String connectionId;
    private String connectionName;
    private String dbType;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String defaultDatabase;
    private String sslMode;
    private String tlsServerName;
    private String caCertificatePem;
    private String tunnelSshConnectionId;
    private Integer connectTimeout;
    private Integer queryTimeout;
    private Integer maxRows;
    private Long configVersion;
    private String aiDataMode;
    private String aiAllowedColumns;
    private Integer status;
    private String userId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
    @TableLogic(value = "0", delval = "1")
    @TableField(insertStrategy = FieldStrategy.NEVER)
    private Integer deleted;
}
