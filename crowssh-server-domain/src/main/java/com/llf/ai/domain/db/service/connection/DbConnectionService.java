package com.llf.ai.domain.db.service.connection;

import com.llf.ai.domain.db.adapter.port.IDbSessionPort;
import com.llf.ai.domain.db.adapter.repository.IDbConnectionRepository;
import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.valobj.CaCertificateActionEnum;
import com.llf.ai.domain.db.model.valobj.PasswordActionEnum;
import com.llf.ai.domain.db.service.IDbConnectionService;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DbConnectionService implements IDbConnectionService {
    private final IDbConnectionRepository repository;
    private final IDbSessionPort sessionPort;
    private final ISshConnectionOwnershipService sshOwnershipService;
    private com.llf.ai.domain.db.service.session.DbSessionService sessionGovernance;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSessionGovernance(com.llf.ai.domain.db.service.session.DbSessionService governance) {
        this.sessionGovernance = Objects.requireNonNull(governance);
    }

    private void mutate(String owner, String connectionId, Runnable action) {
        if (sessionGovernance == null) { action.run(); return; }
        sessionGovernance.mutateConnection(owner, connectionId, () -> { action.run(); return null; });
    }

    @Override
    public DbConnectionEntity create(String ownerId, DbConnectionEntity draft,
                                     PasswordActionEnum passwordAction,
                                     CaCertificateActionEnum caAction) {
        String owner = requireOwner(ownerId);
        DbConnectionEntity entity = draft == null ? new DbConnectionEntity() : draft;
        applyDefaultsAndSecrets(entity, null, passwordAction, caAction, true);
        entity.setUserId(owner);
        entity.setConnectionId(UUID.randomUUID().toString().replace("-", ""));
        entity.setStatus(0);
        entity.setConfigVersion(1L);
        validateTunnel(owner, entity);
        repository.save(entity);
        entity.setPasswordPresent(entity.hasPassword());
        entity.setPassword(null);
        return entity;
    }

    @Override
    public DbConnectionEntity update(String ownerId, DbConnectionEntity draft,
                                     long expectedConfigVersion,
                                     PasswordActionEnum passwordAction,
                                     CaCertificateActionEnum caAction) {
        String owner = requireOwner(ownerId);
        if (draft == null || draft.getConnectionId() == null || draft.getConnectionId().isBlank()) {
            throw new IllegalArgumentException("连接ID不能为空");
        }
        DbConnectionEntity existing = requireOwned(owner, draft.getConnectionId());
        if (expectedConfigVersion < 1) throw new IllegalArgumentException("expectedConfigVersion 不合法");
        DbConnectionEntity entity = merge(draft, existing);
        applyDefaultsAndSecrets(entity, existing, passwordAction, caAction, false);
        entity.setUserId(owner);
        entity.setConfigVersion(existing.getConfigVersion() + 1);
        validateTunnel(owner, entity);
        mutate(owner, entity.getConnectionId(), () -> {
            if (!repository.update(owner, entity, expectedConfigVersion)) {
                throw new DbConfigConflictException("数据库连接配置已被其他窗口修改");
            }
        });
        entity.setPasswordPresent(entity.hasPassword());
        entity.setPassword(null);
        return entity;
    }

    @Override
    public void delete(String ownerId, String connectionId) {
        String owner = requireOwner(ownerId);
        requireOwned(owner, connectionId);
        mutate(owner, connectionId, () -> {
            if (!repository.delete(owner, connectionId)) throw new IllegalArgumentException("连接不存在");
        });
    }

    @Override
    public DbConnectionEntity get(String ownerId, String connectionId) {
        DbConnectionEntity entity = requireOwned(requireOwner(ownerId), connectionId);
        entity.setPassword(null);
        return entity;
    }

    @Override
    public List<DbConnectionEntity> list(String ownerId) {
        return repository.findAll(requireOwner(ownerId)).stream().peek(entity -> entity.setPassword(null)).toList();
    }

    @Override
    public IDbSessionPort.TestConnectionResult test(String ownerId, DbConnectionEntity draft,
                                                      String connectionId,
                                                      PasswordActionEnum passwordAction,
                                                      CaCertificateActionEnum caAction) {
        String owner = requireOwner(ownerId);
        DbConnectionEntity existing = connectionId == null || connectionId.isBlank()
                ? null : requireOwned(owner, connectionId);
        DbConnectionEntity candidate = merge(draft == null ? new DbConnectionEntity() : draft, existing);
        applyDefaultsAndSecrets(candidate, existing, passwordAction, caAction, existing == null);
        validateTunnel(owner, candidate);
        candidate.setUserId(owner);
        try { return sessionPort.test(candidate); }
        finally { candidate.setPassword(null); }
    }

    private void applyDefaultsAndSecrets(DbConnectionEntity target, DbConnectionEntity existing,
                                         PasswordActionEnum passwordAction,
                                         CaCertificateActionEnum caAction,
                                         boolean creating) {
        PasswordActionEnum password = passwordAction == null
                ? (creating ? null : PasswordActionEnum.KEEP) : passwordAction;
        if (creating && password == PasswordActionEnum.KEEP) {
            throw new IllegalArgumentException("创建连接不能使用 KEEP 密码动作");
        }
        if (password == null) throw new IllegalArgumentException("必须明确选择密码动作");
        switch (password) {
            case KEEP -> {
                if (existing == null) throw new IllegalArgumentException("新连接不能保留不存在的密码");
                if (target.getPassword() != null) throw new IllegalArgumentException("KEEP 不能同时提供密码");
                target.setPasswordCiphertext(existing.getPasswordCiphertext());
                target.setPasswordPresent(existing.hasPassword());
            }
            case REPLACE -> {
                if (target.getPassword() == null || target.getPassword().isEmpty()) {
                    throw new IllegalArgumentException("REPLACE 必须提供非空密码");
                }
            }
            case CLEAR -> {
                if (target.getPassword() != null) throw new IllegalArgumentException("CLEAR 不能同时提供密码");
                target.setPasswordCiphertext(null);
                target.setPasswordPresent(false);
            }
        }

        CaCertificateActionEnum ca = caAction == null
                ? (creating ? CaCertificateActionEnum.CLEAR : CaCertificateActionEnum.KEEP) : caAction;
        switch (ca) {
            case KEEP -> {
                if (existing == null) throw new IllegalArgumentException("新连接不能保留不存在的 CA");
                if (target.getCaCertificatePem() != null) throw new IllegalArgumentException("KEEP 不能同时提供 CA");
                target.setCaCertificatePem(existing.getCaCertificatePem());
            }
            case REPLACE -> {
                if (target.getCaCertificatePem() == null || target.getCaCertificatePem().isBlank()) {
                    throw new IllegalArgumentException("REPLACE 必须提供 CA 证书");
                }
            }
            case CLEAR -> {
                if (target.getCaCertificatePem() != null) throw new IllegalArgumentException("CLEAR 不能同时提供 CA");
            }
        }
        target.applyDefaults();
        target.validate();
    }

    private DbConnectionEntity merge(DbConnectionEntity draft, DbConnectionEntity existing) {
        if (existing == null) return draft;
        if (draft.getConnectionName() == null) draft.setConnectionName(existing.getConnectionName());
        if (draft.getDbType() == null) draft.setDbType(existing.getDbType());
        if (draft.getHost() == null) draft.setHost(existing.getHost());
        if (draft.getPort() == null) draft.setPort(existing.getPort());
        if (draft.getUsername() == null) draft.setUsername(existing.getUsername());
        if (draft.getDefaultDatabase() == null) draft.setDefaultDatabase(existing.getDefaultDatabase());
        if (draft.getSslMode() == null) draft.setSslMode(existing.getSslMode());
        if (draft.getTlsServerName() == null) draft.setTlsServerName(existing.getTlsServerName());
        if (draft.getTunnelSshConnectionId() == null) draft.setTunnelSshConnectionId(existing.getTunnelSshConnectionId());
        if (draft.getConnectTimeout() == null) draft.setConnectTimeout(existing.getConnectTimeout());
        if (draft.getQueryTimeout() == null) draft.setQueryTimeout(existing.getQueryTimeout());
        if (draft.getMaxRows() == null) draft.setMaxRows(existing.getMaxRows());
        if (draft.getAiDataMode() == null) {
            draft.setAiDataMode(existing.getAiDataMode());
            draft.setAiAllowedColumns(existing.getAiAllowedColumns());
        }
        draft.setStatus(existing.getStatus());
        return draft;
    }

    private void validateTunnel(String owner, DbConnectionEntity entity) {
        if (entity.getTunnelSshConnectionId() != null && !entity.getTunnelSshConnectionId().isBlank()) {
            sshOwnershipService.requireOwnership(owner, entity.getTunnelSshConnectionId());
        }
    }

    private DbConnectionEntity requireOwned(String owner, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("连接ID不能为空");
        DbConnectionEntity entity = repository.find(owner, connectionId);
        if (entity == null) throw new IllegalArgumentException("连接不存在");
        entity.applyDefaults();
        return entity;
    }

    private String requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("设备身份不能为空");
        return ownerId.trim();
    }
}
