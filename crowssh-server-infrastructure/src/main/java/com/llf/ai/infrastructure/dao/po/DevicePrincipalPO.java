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

/**
 * 设备身份持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("device_principal")
public class DevicePrincipalPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String principalId;
    private String tokenHash;
    private Integer status;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
}
