package com.llf.ai.infrastructure.adapter.repository;

import com.llf.ai.domain.ssh.adapter.repository.ISshConnectionRepository;
import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.llf.ai.domain.ssh.model.valobj.AuthTypeEnum;
import com.llf.ai.domain.ssh.model.valobj.ConnectionStatusEnum;
import com.llf.ai.infrastructure.dao.ISshConnectionConfigDAO;
import com.llf.ai.infrastructure.dao.ISshConnectionDAO;
import com.llf.ai.infrastructure.dao.po.SshConnectionConfigPO;
import com.llf.ai.infrastructure.dao.po.SshConnectionPO;
import com.llf.ai.infrastructure.security.PasswordEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SSH连接仓储实现。
 *
 * @author llf
 */
@Repository
@RequiredArgsConstructor
public class SshConnectionRepository implements ISshConnectionRepository {

    private final ISshConnectionDAO sshConnectionDAO;
    private final ISshConnectionConfigDAO sshConnectionConfigDAO;
    private final PasswordEncryptor passwordEncryptor = new PasswordEncryptor();

    @Override
    public void saveConnection(SshConnectionEntity entity) {
        SshConnectionPO po = toConnectionPO(entity);
        sshConnectionDAO.insert(po);
        entity.setId(po.getId());
    }

    @Override
    public void updateConnection(String ownerId, SshConnectionEntity entity) {
        sshConnectionDAO.update(ownerId, toConnectionPO(entity));
    }

    @Override
    public void deleteConnection(String ownerId, String connectionId) {
        if (sshConnectionDAO.delete(ownerId, connectionId) > 0) {
            sshConnectionConfigDAO.deleteByConnectionId(connectionId);
        }
    }

    @Override
    public SshConnectionEntity queryConnectionById(String ownerId, String connectionId) {
        SshConnectionPO po = sshConnectionDAO.queryByConnectionId(ownerId, connectionId);
        return po == null ? null : toConnectionEntity(po);
    }

    @Override
    public List<SshConnectionEntity> queryConnectionListByUserId(String userId) {
        return sshConnectionDAO.queryListByUserId(userId).stream()
                .map(this::toConnectionEntity)
                .toList();
    }

    @Override
    public void saveConnectionConfig(SshConnectionConfigEntity entity) {
        sshConnectionConfigDAO.insertOrUpdate(toConnectionConfigPO(entity));
    }

    @Override
    public SshConnectionConfigEntity queryConnectionConfigById(String connectionId) {
        SshConnectionConfigPO po = sshConnectionConfigDAO.queryByConnectionId(connectionId);
        return po == null ? null : toConnectionConfigEntity(po);
    }

    private SshConnectionPO toConnectionPO(SshConnectionEntity entity) {
        int encrypted = entity.getEncrypted() == null ? 1 : entity.getEncrypted();
        return SshConnectionPO.builder()
                .id(entity.getId())
                .connectionId(entity.getConnectionId())
                .connectionName(entity.getConnectionName())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .authType(entity.getAuthType() == null ? AuthTypeEnum.PASSWORD.getCode() : entity.getAuthType().getCode())
                .password(encryptCredential(entity.getPassword(), encrypted))
                .privateKey(encryptCredential(entity.getPrivateKey(), encrypted))
                .encrypted(encrypted)
                .status(entity.getStatus() == null ? ConnectionStatusEnum.DISCONNECTED.getCode() : entity.getStatus().getCode())
                .userId(entity.getUserId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private SshConnectionEntity toConnectionEntity(SshConnectionPO po) {
        return SshConnectionEntity.builder()
                .id(po.getId())
                .connectionId(po.getConnectionId())
                .connectionName(po.getConnectionName())
                .host(po.getHost())
                .port(po.getPort())
                .username(po.getUsername())
                .authType(po.getAuthType() == null ? AuthTypeEnum.PASSWORD : AuthTypeEnum.fromCode(po.getAuthType()))
                .password(decryptCredential(po.getPassword(), po.getEncrypted()))
                .privateKey(decryptCredential(po.getPrivateKey(), po.getEncrypted()))
                .encrypted(po.getEncrypted())
                .status(po.getStatus() == null ? ConnectionStatusEnum.DISCONNECTED : ConnectionStatusEnum.fromCode(po.getStatus()))
                .userId(po.getUserId())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private SshConnectionConfigPO toConnectionConfigPO(SshConnectionConfigEntity entity) {
        return SshConnectionConfigPO.builder()
                .id(entity.getId())
                .connectionId(entity.getConnectionId())
                .connectTimeout(entity.getConnectTimeout())
                .keepaliveInterval(entity.getKeepaliveInterval())
                .startupCommand(entity.getStartupCommand())
                .compression(toInteger(entity.getCompression()))
                .strictHostKeyCheck(toInteger(entity.getStrictHostKeyCheck()))
                .knownHosts(entity.getKnownHosts())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private SshConnectionConfigEntity toConnectionConfigEntity(SshConnectionConfigPO po) {
        return SshConnectionConfigEntity.builder()
                .id(po.getId())
                .connectionId(po.getConnectionId())
                .connectTimeout(po.getConnectTimeout())
                .keepaliveInterval(po.getKeepaliveInterval())
                .startupCommand(po.getStartupCommand())
                .compression(toBoolean(po.getCompression()))
                .strictHostKeyCheck(toBoolean(po.getStrictHostKeyCheck()))
                .knownHosts(po.getKnownHosts())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private String encryptCredential(String value, int encrypted) {
        return encrypted == 1 ? passwordEncryptor.encrypt(value) : value;
    }

    private String decryptCredential(String value, Integer encrypted) {
        return Integer.valueOf(1).equals(encrypted) ? passwordEncryptor.decrypt(value) : value;
    }

    private Integer toInteger(Boolean value) {
        return value == null ? null : (value ? 1 : 0);
    }

    private Boolean toBoolean(Integer value) {
        return value == null ? null : value == 1;
    }
}
