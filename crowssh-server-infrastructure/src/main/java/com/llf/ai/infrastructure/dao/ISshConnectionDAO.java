package com.llf.ai.infrastructure.dao;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llf.ai.infrastructure.dao.po.SshConnectionPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * SSH连接配置DAO
 *
 * @author llf
 */
@Mapper
public interface ISshConnectionDAO extends BaseMapper<SshConnectionPO> {

    /**
     * 更新SSH连接配置
     *
     * @param po 连接配置对象
     * @return 影响行数
     */
    default int update(SshConnectionPO po) {
        LambdaUpdateWrapper<SshConnectionPO> wrapper = Wrappers.lambdaUpdate(SshConnectionPO.class)
                .set(SshConnectionPO::getConnectionName, po.getConnectionName())
                .set(SshConnectionPO::getHost, po.getHost())
                .set(SshConnectionPO::getPort, po.getPort())
                .set(SshConnectionPO::getUsername, po.getUsername())
                .set(SshConnectionPO::getAuthType, po.getAuthType())
                .set(SshConnectionPO::getPassword, po.getPassword())
                .set(SshConnectionPO::getPrivateKey, po.getPrivateKey())
                .set(SshConnectionPO::getEncrypted, po.getEncrypted())
                .set(SshConnectionPO::getStatus, po.getStatus())
                .eq(SshConnectionPO::getConnectionId, po.getConnectionId());
        return update(wrapper);
    }

    /**
     * 根据连接ID删除SSH连接配置（逻辑删除）
     *
     * @param connectionId 连接唯一标识
     * @return 影响行数
     */
    default int delete(String connectionId) {
        return delete(Wrappers.<SshConnectionPO>lambdaQuery()
                .eq(SshConnectionPO::getConnectionId, connectionId));
    }

    /**
     * 根据连接ID查询SSH连接配置
     *
     * @param connectionId 连接唯一标识
     * @return 连接配置对象
     */
    default SshConnectionPO queryByConnectionId(String connectionId) {
        return selectOne(Wrappers.<SshConnectionPO>lambdaQuery()
                .eq(SshConnectionPO::getConnectionId, connectionId));
    }

    /**
     * 根据用户ID查询SSH连接配置列表
     *
     * @param userId 用户ID
     * @return 连接配置列表
     */
    default List<SshConnectionPO> queryListByUserId(String userId) {
        return selectList(Wrappers.<SshConnectionPO>lambdaQuery()
                .eq(SshConnectionPO::getUserId, userId)
                .orderByDesc(SshConnectionPO::getCreatedAt));
    }
}
