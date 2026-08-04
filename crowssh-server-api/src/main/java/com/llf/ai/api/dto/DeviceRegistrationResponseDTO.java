package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备身份注册响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegistrationResponseDTO {

    /** 服务端生成的设备身份 ID。 */
    private String principalId;

    /** 仅在注册时返回一次的访问令牌。 */
    private String accessToken;
}
