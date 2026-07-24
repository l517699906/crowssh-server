package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.llf.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SSH连接领域服务实现
 *
 * @author waissh dev
 */
@Slf4j
@Service
public class SshConnectionService implements ISshConnectionDomainService{

    private final ISshConnectionRepository repository;
    private final ISshSessionPort sshSessionService;

    public SshConnectionService(ISshConnectionRepository repository, ISshSessionPort sshSessionService) {
        this.repository = repository;
        this.sshSessionService = sshSessionService;
    }

    @Override
    public void createConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        // 1. 设置默认值
        if (entity.getPort() == null) {
            entity.setPort(22);
        }
        entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
        if (entity.getEncrypted() == null) {
            entity.setEncrypted(1);
        }
        if (entity.getUserId() == null || entity.getUserId().isBlank()) {
            entity.setUserId("default");
        }

        // 2. 校验必填字段
        entity.validate();

        // 3. 生成连接ID
        if (entity.getConnectionId() == null || entity.getConnectionId().isBlank()) {
            entity.setConnectionId(UUID.randomUUID().toString().replace("-", ""));
        }

        // 4. 保存连接
        repository.saveConnection(entity);

        // 5. 保存高级配置
        if (configEntity != null) {
            configEntity.setConnectionId(entity.getConnectionId());
            configEntity.withDefaults();
            repository.saveConnectionConfig(configEntity);
        }

        log.info("SSH连接创建成功 connectionId={}", entity.getConnectionId());
    }

    @Override
    public void updateConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity) {
        // 1. 校验必填字段
        entity.validate();

        // 2. 检查连接是否存在，并获取原有数据
        SshConnectionEntity existing = repository.queryConnectionById(entity.getConnectionId());
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

        // 4. 更新连接
        repository.updateConnection(entity);

        // 5. 更新高级配置
        if (configEntity != null) {
            configEntity.setConnectionId(entity.getConnectionId());
            mergeMissingConfig(configEntity, repository.queryConnectionConfigById(entity.getConnectionId()));
            repository.saveConnectionConfig(configEntity);
        }

        log.info("SSH连接更新成功 connectionId={}", entity.getConnectionId());
    }

    @Override
    public void deleteConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("连接ID不能为空");
        }
        sshSessionService.disconnect(connectionId);
        repository.deleteConnection(connectionId);
        log.info("SSH连接删除成功 connectionId={}", connectionId);
    }

    @Override
    public SshConnectionEntity getConnection(String connectionId) {
        return repository.queryConnectionById(connectionId);
    }

    @Override
    public List<SshConnectionEntity> getConnectionList(String userId) {
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        return repository.queryConnectionListByUserId(userId);
    }

    @Override
    public SshConnectionConfigEntity getConnectionConfig(String connectionId) {
        return repository.queryConnectionConfigById(connectionId);
    }

    @Override
    public boolean connect(String connectionId) {
        // 1. 查询连接信息
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity == null) {
            throw new IllegalArgumentException("连接不存在");
        }

        // 2. 建立 SSH 连接
        boolean success = sshSessionService.connect(
                connectionId,
                entity.getHost(),
                entity.getPort(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getPrivateKey()
        );

        // 3. 更新连接状态
        entity.setStatus(success ? ConnectionStatusEnum.CONNECTED : ConnectionStatusEnum.FAILED);
        repository.updateConnection(entity);

        return success;
    }

    @Override
    public void disconnect(String connectionId) {
        // 1. 断开 SSH 连接
        sshSessionService.disconnect(connectionId);

        // 2. 更新连接状态
        SshConnectionEntity entity = repository.queryConnectionById(connectionId);
        if (entity != null) {
            entity.setStatus(ConnectionStatusEnum.DISCONNECTED);
            repository.updateConnection(entity);
        }
    }

    @Override
    public boolean isConnected(String connectionId) {
        return sshSessionService.isConnected(connectionId);
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
}
