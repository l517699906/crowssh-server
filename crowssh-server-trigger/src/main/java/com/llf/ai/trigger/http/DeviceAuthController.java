package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.DeviceRegistrationResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.auth.model.valobj.DeviceRegistration;
import com.llf.ai.domain.auth.service.DeviceIdentityService;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无登录界面的客户端安装身份注册入口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/device")
public class DeviceAuthController {

    private final DeviceIdentityService deviceIdentityService;

    public DeviceAuthController(DeviceIdentityService deviceIdentityService) {
        this.deviceIdentityService = deviceIdentityService;
    }

    @PostMapping("/register")
    public Response<DeviceRegistrationResponseDTO> register() {
        DeviceRegistration registration = deviceIdentityService.register();
        log.info("设备身份注册成功 principalId={}", registration.principalId());
        return Response.<DeviceRegistrationResponseDTO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(DeviceRegistrationResponseDTO.builder()
                        .principalId(registration.principalId())
                        .accessToken(registration.accessToken())
                        .build())
                .build();
    }
}
