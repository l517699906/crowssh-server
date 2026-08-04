package com.llf.ai.infrastructure.adapter.repository;

import com.llf.ai.domain.auth.adapter.repository.IDevicePrincipalRepository;
import com.llf.ai.domain.auth.model.entity.DevicePrincipalEntity;
import com.llf.ai.infrastructure.dao.IDevicePrincipalDAO;
import com.llf.ai.infrastructure.dao.po.DevicePrincipalPO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 设备身份仓储实现。
 */
@Repository
public class DevicePrincipalRepository implements IDevicePrincipalRepository {

    private final IDevicePrincipalDAO devicePrincipalDAO;

    public DevicePrincipalRepository(IDevicePrincipalDAO devicePrincipalDAO) {
        this.devicePrincipalDAO = devicePrincipalDAO;
    }

    @Override
    public void save(DevicePrincipalEntity entity) {
        DevicePrincipalPO po = DevicePrincipalPO.builder()
                .principalId(entity.getPrincipalId())
                .tokenHash(entity.getTokenHash())
                .status(entity.getStatus())
                .revokedAt(entity.getRevokedAt())
                .build();
        devicePrincipalDAO.insert(po);
        entity.setId(po.getId());
    }

    @Override
    public Optional<DevicePrincipalEntity> queryActiveByTokenHash(String tokenHash) {
        DevicePrincipalPO po = devicePrincipalDAO.queryActiveByTokenHash(tokenHash);
        if (po == null) {
            return Optional.empty();
        }
        return Optional.of(DevicePrincipalEntity.builder()
                .id(po.getId())
                .principalId(po.getPrincipalId())
                .tokenHash(po.getTokenHash())
                .status(po.getStatus())
                .createdAt(po.getCreatedAt())
                .revokedAt(po.getRevokedAt())
                .build());
    }
}
