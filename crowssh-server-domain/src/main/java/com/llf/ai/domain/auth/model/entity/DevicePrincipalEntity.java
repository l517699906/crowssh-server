package com.llf.ai.domain.auth.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 客户端安装实例对应的认证身份。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevicePrincipalEntity {

    private Long id;
    private String principalId;
    private String tokenHash;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
}
