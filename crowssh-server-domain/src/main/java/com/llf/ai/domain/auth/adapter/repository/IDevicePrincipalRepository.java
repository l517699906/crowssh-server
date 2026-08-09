package com.llf.ai.domain.auth.adapter.repository;

import com.llf.ai.domain.auth.model.entity.DevicePrincipalEntity;

import java.util.Optional;

/**
 * 设备身份仓储。
 */
public interface IDevicePrincipalRepository {

    void save(DevicePrincipalEntity entity);

    Optional<DevicePrincipalEntity> queryActiveByTokenHash(String tokenHash);

    long countActive();
}
