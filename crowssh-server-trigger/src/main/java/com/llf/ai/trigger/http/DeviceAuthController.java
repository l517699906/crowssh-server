package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.DeviceRegistrationResponseDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.auth.model.valobj.DeviceRegistration;
import com.llf.ai.domain.auth.service.DeviceIdentityService;
import com.llf.ai.domain.auth.service.DeviceRegistrationQuotaExceededException;
import com.llf.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final DeviceRegistrationGuard registrationGuard;

    public DeviceAuthController(
            DeviceIdentityService deviceIdentityService,
            DeviceRegistrationGuard registrationGuard
    ) {
        this.deviceIdentityService = deviceIdentityService;
        this.registrationGuard = registrationGuard;
    }

    @PostMapping("/register")
    public ResponseEntity<Response<DeviceRegistrationResponseDTO>> register(
            HttpServletRequest request,
            @RequestHeader(value = "X-CrowSSH-Registration-Code", required = false) String inviteCode
    ) {
        try {
            registrationGuard.check(request.getRemoteAddr(), inviteCode);
            DeviceRegistration registration = deviceIdentityService.register(
                    registrationGuard.maxActivePrincipals());
            log.info("设备身份注册成功 principalId={}", registration.principalId());
            return ResponseEntity.ok(Response.<DeviceRegistrationResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(DeviceRegistrationResponseDTO.builder()
                            .principalId(registration.principalId())
                            .accessToken(registration.accessToken())
                            .build())
                    .build());
        } catch (DeviceRegistrationRejectedException e) {
            HttpHeaders headers = new HttpHeaders();
            if (e.getRetryAfterSeconds() > 0) {
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
            }
            return new ResponseEntity<>(error(e.getResponseCode()), headers, e.getStatus());
        } catch (DeviceRegistrationQuotaExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(error(ResponseCode.DEVICE_REGISTRATION_QUOTA_EXCEEDED));
        }
    }

    private Response<DeviceRegistrationResponseDTO> error(ResponseCode code) {
        return Response.<DeviceRegistrationResponseDTO>builder()
                .code(code.getCode())
                .info(code.getInfo())
                .build();
    }
}
