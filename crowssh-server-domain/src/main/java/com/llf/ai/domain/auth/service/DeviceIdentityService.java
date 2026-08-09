package com.llf.ai.domain.auth.service;

import com.llf.ai.domain.auth.adapter.repository.IDevicePrincipalRepository;
import com.llf.ai.domain.auth.model.entity.DevicePrincipalEntity;
import com.llf.ai.domain.auth.model.valobj.DeviceRegistration;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备身份注册与令牌认证服务。
 */
@Service
public class DeviceIdentityService {

    private static final int TOKEN_BYTES = 32;
    private static final int ACTIVE = 1;

    private final IDevicePrincipalRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceIdentityService(IDevicePrincipalRepository repository) {
        this.repository = repository;
    }

    public DeviceRegistration register() {
        return register(Integer.MAX_VALUE);
    }

    public synchronized DeviceRegistration register(int maxActivePrincipals) {
        if (maxActivePrincipals <= 0) {
            throw new IllegalArgumentException("设备身份配额必须大于 0");
        }
        if (repository.countActive() >= maxActivePrincipals) {
            throw new DeviceRegistrationQuotaExceededException(maxActivePrincipals);
        }

        String principalId = UUID.randomUUID().toString().replace("-", "");
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        repository.save(DevicePrincipalEntity.builder()
                .principalId(principalId)
                .tokenHash(hashToken(accessToken))
                .status(ACTIVE)
                .build());
        return new DeviceRegistration(principalId, accessToken);
    }

    public Optional<String> authenticate(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        return repository.queryActiveByTokenHash(hashToken(accessToken.trim()))
                .map(DevicePrincipalEntity::getPrincipalId);
    }

    static String hashToken(String accessToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(accessToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }
}
