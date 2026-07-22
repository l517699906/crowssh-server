package com.llf.ai.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
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
@TableName("ssh_connection_config")
public class SshConnectionConfigPO {

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 关联的连接ID */
    private String connectionId;
    /** 连接超时时间(秒) */
    private Integer connectTimeout;
    /** 保活间隔(秒) */
    private Integer keepaliveInterval;
    /** 连接后执行的启动命令 */
    private String startupCommand;
    /** 是否压缩:0-否,1-是 */
    private Integer compression;
    /** 严格主机密钥检查:0-否,1-是 */
    private Integer strictHostKeyCheck;
    /** 已知主机密钥列表 */
    private String knownHosts;
    /** 更新时间 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
