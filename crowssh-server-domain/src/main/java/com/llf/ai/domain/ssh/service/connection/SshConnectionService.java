package com.llf.ai.domain.ssh.service.connection;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.llf.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import com.llf.ai.domain.ssh.service.ISshConnectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SSH连接领域服务实现
 *
 * @author llf
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionService, ISshConnectionOwnershipService {

    private final ISshConnectionRepository repository;
    private final ISshSessionPort sshSessionService;

    public SshConnectionService(ISshConnectionRepository repository, ISshSessionPort sshSessionService) {
        this.repository = repository;
        this.sshSessionService = sshSessionService;
    }

    @Override
    public void createConnection(String ownerId, SshConnectionEntity entity,
                                 SshConnectionConfigEntity configEntity) {
        entity.setUserId(requireOwnerId(ownerId));
        // 1. 设置默认值
        if (entity.getPort() == null) {
            entity.setPort(22);
        }
        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
        if (entity.getEncrypted() == null) {
            entity.setEncrypted(1);
        }
        // 2. 校验必填字段
        entity.validate();

        // 3. 连接 ID 只能由服务端生成，避免调用方控制全局运行时资源键。
        entity.setConnectionId(UUID.randomUUID().toString().replace("-", ""));

        // 4. 保存连接
        repository.saveConnection(entity);

        // 5. 保存高级配置
        configEntity = prepareConfig(configEntity);
        configEntity.setConnectionId(entity.getConnectionId());
        repository.saveConnectionConfig(configEntity);

        log.info("SSH连接创建成功 connectionId={}", entity.getConnectionId());
    }

    @Override
    public void updateConnection(String ownerId, SshConnectionEntity entity,
                                 SshConnectionConfigEntity configEntity) {
        String normalizedOwnerId = requireOwnerId(ownerId);
        entity.setUserId(normalizedOwnerId);
        // 1. 校验必填字段
        entity.validate();

        // 2. 检查连接是否存在，并获取原有数据
        SshConnectionEntity existing = repository.queryConnectionById(
                normalizedOwnerId, entity.getConnectionId());
        if (existing == null) {
            throw new IllegalArgumentException("连接不存在");
        }

        // 3. 密码/私钥留空则保留原值
        if (entity.getPassword() == null || entity.getPassword().isEmpty()) {
            entity.setPassword(existing.getPassword());
        }
        if (entity.getPrivateKey() == null || entity.getPrivateKey().isEmpty()) {
            entity.setPrivateKey(existing.getPrivateKey());
        }
        // encrypted 保留原值
        if (entity.getEncrypted() == null) {
            entity.setEncrypted(existing.getEncrypted());
        }

        // 4. 更新连接后断开旧会话，确保新地址和运行时配置立即生效
        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
        repository.updateConnection(normalizedOwnerId, entity);

        // 5. 更新高级配置
        if (configEntity != null) {
            configEntity.setConnectionId(entity.getConnectionId());
            mergeMissingConfig(configEntity, repository.queryConnectionConfigById(entity.getConnectionId()));
            prepareConfig(configEntity);
            repository.saveConnectionConfig(configEntity);
        }

        sshSessionService.disconnect(entity.getConnectionId());

        log.info("SSH连接更新成功 connectionId={}", entity.getConnectionId());
    }

    @Override
    public void deleteConnection(String ownerId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("连接ID不能为空");
        }
        String normalizedOwnerId = requireOwnerId(ownerId);
        requireOwnedConnection(normalizedOwnerId, connectionId);
        sshSessionService.disconnect(connectionId);
        repository.deleteConnection(normalizedOwnerId, connectionId);
        log.info("SSH连接删除成功 connectionId={}", connectionId);
    }

    @Override
    public SshConnectionEntity getConnection(String ownerId, String connectionId) {
        return repository.queryConnectionById(requireOwnerId(ownerId), connectionId);
    }

    @Override
    public List<SshConnectionEntity> getConnectionList(String ownerId) {
        return repository.queryConnectionListByUserId(requireOwnerId(ownerId));
    }

    @Override
    public SshConnectionConfigEntity getConnectionConfig(String ownerId, String connectionId) {
        requireOwnedConnection(requireOwnerId(ownerId), connectionId);
        return repository.queryConnectionConfigById(connectionId);
    }

    @Override
    public void testConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        if (entity.getPort() == null) {
            entity.setPort(22);
        }
        entity.validate();
        sshSessionService.testConnection(entity, prepareConfig(configEntity));
    }

    @Override
    public boolean connect(String ownerId, String connectionId) {
        String normalizedOwnerId = requireOwnerId(ownerId);
        // 1. 查询连接信息
        SshConnectionEntity entity = repository.queryConnectionById(normalizedOwnerId, connectionId);
        if (entity == null) {
            throw new IllegalArgumentException("连接不存在");
        }

        // 2. 建立 SSH 连接
        SshConnectionConfigEntity configEntity = prepareConfig(
                repository.queryConnectionConfigById(connectionId));
        boolean success = sshSessionService.connect(entity, configEntity);

        // 3. 更新连接状态
        entity.setStatus(success ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED);
        repository.updateConnection(normalizedOwnerId, entity);

        return success;
    }

    @Override
    public void disconnect(String ownerId, String connectionId) {
        String normalizedOwnerId = requireOwnerId(ownerId);
        SshConnectionEntity entity = requireOwnedConnection(normalizedOwnerId, connectionId);
        // 1. 断开 SSH 连接
        sshSessionService.disconnect(connectionId);

        // 2. 更新连接状态
        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
        repository.updateConnection(normalizedOwnerId, entity);
    }

    @Override
    public boolean isConnected(String ownerId, String connectionId) {
        requireOwnedConnection(requireOwnerId(ownerId), connectionId);
        return sshSessionService.isConnected(connectionId);
    }

    @Override
    public void requireOwnership(String ownerId, String connectionId) {
        requireOwnedConnection(requireOwnerId(ownerId), connectionId);
    }

    private SshConnectionEntity requireOwnedConnection(String ownerId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("连接ID不能为空");
        }
        SshConnectionEntity entity = repository.queryConnectionById(ownerId, connectionId);
        if (entity == null) {
            throw new IllegalArgumentException("连接不存在");
        }
        return entity;
    }

    private String requireOwnerId(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("设备身份不能为空");
        }
        return ownerId.trim();
    }

    private void mergeMissingConfig(SshConnectionConfigEntity target, SshConnectionConfigEntity existing) {
        if (existing == null) {
            target.withDefaults();
            return;
        }
        if (target.getConnectTimeout() == null) {
            target.setConnectTimeout(existing.getConnectTimeout());
        }
        if (target.getKeepaliveInterval() == null) {
            target.setKeepaliveInterval(existing.getKeepaliveInterval());
        }
        if (target.getStartupCommand() == null) {
            target.setStartupCommand(existing.getStartupCommand());
        }
        if (target.getCompression() == null) {
            target.setCompression(existing.getCompression());
        }
        if (target.getStrictHostKeyCheck() == null) {
            target.setStrictHostKeyCheck(existing.getStrictHostKeyCheck());
        }
        if (target.getKnownHosts() == null) {
            target.setKnownHosts(existing.getKnownHosts());
        }
    }

    private SshConnectionConfigEntity prepareConfig(SshConnectionConfigEntity configEntity) {
        SshConnectionConfigEntity effectiveConfig = configEntity == null
                ? SshConnectionConfigEntity.builder().build()
                : configEntity;
        effectiveConfig.withDefaults();
        effectiveConfig.validate();
        return effectiveConfig;
    }
}
