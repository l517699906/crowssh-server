package com.llf.ai.infrastructure.dao;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llf.ai.infrastructure.dao.po.DbConnectionPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IDbConnectionDAO extends BaseMapper<DbConnectionPO> {
    default int updateOwned(String ownerId, DbConnectionPO po, long expectedVersion) {
        LambdaUpdateWrapper<DbConnectionPO> wrapper = Wrappers.lambdaUpdate(DbConnectionPO.class)
                .set(DbConnectionPO::getConnectionName, po.getConnectionName())
                .set(DbConnectionPO::getDbType, po.getDbType())
                .set(DbConnectionPO::getHost, po.getHost())
                .set(DbConnectionPO::getPort, po.getPort())
                .set(DbConnectionPO::getUsername, po.getUsername())
                .set(DbConnectionPO::getPassword, po.getPassword())
                .set(DbConnectionPO::getDefaultDatabase, po.getDefaultDatabase())
                .set(DbConnectionPO::getSslMode, po.getSslMode())
                .set(DbConnectionPO::getTlsServerName, po.getTlsServerName())
                .set(DbConnectionPO::getCaCertificatePem, po.getCaCertificatePem())
                .set(DbConnectionPO::getTunnelSshConnectionId, po.getTunnelSshConnectionId())
                .set(DbConnectionPO::getConnectTimeout, po.getConnectTimeout())
                .set(DbConnectionPO::getQueryTimeout, po.getQueryTimeout())
                .set(DbConnectionPO::getMaxRows, po.getMaxRows())
                .set(DbConnectionPO::getConfigVersion, po.getConfigVersion())
                .set(DbConnectionPO::getAiDataMode, po.getAiDataMode())
                .set(DbConnectionPO::getAiAllowedColumns, po.getAiAllowedColumns())
                .set(DbConnectionPO::getStatus, po.getStatus())
                .eq(DbConnectionPO::getUserId, ownerId)
                .eq(DbConnectionPO::getConnectionId, po.getConnectionId())
                .eq(DbConnectionPO::getConfigVersion, expectedVersion);
        return update(wrapper);
    }

    default int deleteOwned(String ownerId, String connectionId) {
        return delete(Wrappers.<DbConnectionPO>lambdaQuery()
                .eq(DbConnectionPO::getUserId, ownerId)
                .eq(DbConnectionPO::getConnectionId, connectionId));
    }

    default DbConnectionPO findOwned(String ownerId, String connectionId) {
        return selectOne(Wrappers.<DbConnectionPO>lambdaQuery()
                .eq(DbConnectionPO::getUserId, ownerId)
                .eq(DbConnectionPO::getConnectionId, connectionId));
    }

    default List<DbConnectionPO> findAllOwned(String ownerId) {
        return selectList(Wrappers.<DbConnectionPO>lambdaQuery()
                .eq(DbConnectionPO::getUserId, ownerId)
                .orderByDesc(DbConnectionPO::getCreatedAt));
    }
}
