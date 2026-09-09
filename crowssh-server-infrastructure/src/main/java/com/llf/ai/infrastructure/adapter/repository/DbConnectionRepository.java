package com.llf.ai.infrastructure.adapter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.db.adapter.repository.IDbConnectionRepository;
import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.valobj.AiDataModeEnum;
import com.llf.ai.domain.db.model.valobj.DbAllowedColumn;
import com.llf.ai.domain.db.model.valobj.DbTypeEnum;
import com.llf.ai.domain.db.model.valobj.SslModeEnum;
import com.llf.ai.infrastructure.dao.IDbConnectionDAO;
import com.llf.ai.infrastructure.dao.po.DbConnectionPO;
import com.llf.ai.infrastructure.security.PasswordEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DbConnectionRepository implements IDbConnectionRepository {
    private final IDbConnectionDAO dao;
    private final PasswordEncryptor passwordEncryptor;
    private final ObjectMapper objectMapper;

    @Override
    public void save(DbConnectionEntity entity) {
        DbConnectionPO po = toPO(entity);
        dao.insert(po);
        entity.setId(po.getId());
    }

    @Override
    public boolean update(String ownerId, DbConnectionEntity entity, long expectedConfigVersion) {
        return dao.updateOwned(ownerId, toPO(entity), expectedConfigVersion) > 0;
    }

    @Override
    public boolean delete(String ownerId, String connectionId) {
        return dao.deleteOwned(ownerId, connectionId) > 0;
    }

    @Override
    public DbConnectionEntity find(String ownerId, String connectionId) {
        DbConnectionPO po = dao.findOwned(ownerId, connectionId);
        return po == null ? null : fromPO(po);
    }

    @Override
    public List<DbConnectionEntity> findAll(String ownerId) {
        return dao.findAllOwned(ownerId).stream().map(this::fromPO).toList();
    }

    private DbConnectionPO toPO(DbConnectionEntity entity) {
        return DbConnectionPO.builder()
                .id(entity.getId())
                .connectionId(entity.getConnectionId())
                .connectionName(entity.getConnectionName())
                .dbType(entity.getDbType().name())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .password(entity.getPassword() == null ? entity.getPasswordCiphertext() : passwordEncryptor.encrypt(entity.getPassword()))
                .defaultDatabase(entity.getDefaultDatabase())
                .sslMode(entity.getSslMode().name())
                .tlsServerName(entity.getTlsServerName())
                .caCertificatePem(entity.getCaCertificatePem())
                .tunnelSshConnectionId(entity.getTunnelSshConnectionId())
                .connectTimeout(entity.getConnectTimeout())
                .queryTimeout(entity.getQueryTimeout())
                .maxRows(entity.getMaxRows())
                .configVersion(entity.getConfigVersion())
                .aiDataMode(entity.getAiDataMode().name())
                .aiAllowedColumns(writeRules(entity.getAiAllowedColumns()))
                .status(entity.getStatus())
                .userId(entity.getUserId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private DbConnectionEntity fromPO(DbConnectionPO po) {
        DbConnectionEntity entity = DbConnectionEntity.builder()
                .id(po.getId())
                .connectionId(po.getConnectionId())
                .connectionName(po.getConnectionName())
                .dbType(DbTypeEnum.valueOf(po.getDbType()))
                .host(po.getHost())
                .port(po.getPort())
                .username(po.getUsername())
                .passwordCiphertext(po.getPassword())
                .defaultDatabase(po.getDefaultDatabase())
                .sslMode(SslModeEnum.valueOf(po.getSslMode()))
                .tlsServerName(po.getTlsServerName())
                .caCertificatePem(po.getCaCertificatePem())
                .tunnelSshConnectionId(po.getTunnelSshConnectionId())
                .connectTimeout(po.getConnectTimeout())
                .queryTimeout(po.getQueryTimeout())
                .maxRows(po.getMaxRows())
                .configVersion(po.getConfigVersion())
                .aiDataMode(AiDataModeEnum.valueOf(po.getAiDataMode()))
                .aiAllowedColumns(readRules(po.getAiAllowedColumns()))
                .status(po.getStatus())
                .userId(po.getUserId())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
        entity.setPasswordPresent(po.getPassword() != null && !po.getPassword().isEmpty());
        entity.applyDefaults();
        return entity;
    }

    private String writeRules(List<DbAllowedColumn> rules) {
        try {
            return rules == null || rules.isEmpty() ? null : objectMapper.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库 AI 列白名单序列化失败", e);
        }
    }

    private List<DbAllowedColumn> readRules(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库 AI 列白名单数据损坏", e);
        }
    }
}
