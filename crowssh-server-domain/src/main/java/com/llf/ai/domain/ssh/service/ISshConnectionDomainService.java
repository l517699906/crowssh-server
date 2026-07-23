package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;

import java.util.List;

/**
 * SSH连接领域服务接口
 *
 * @author llf
 */
public interface ISshConnectionDomainService {

    /**
     * 创建SSH连接
     */
    void createConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * 更新SSH连接
     */
    void updateConnection(SshConnectionEntity entity, SshConnectionConfigEntity configEntity);

    /**
     * 删除SSH连接
     */
    void deleteConnection(String connectionId);

    /**
     * 查询单个连接
     */
    SshConnectionEntity getConnection(String connectionId);

    /**
     * 查询用户的所有连接
     */
    List<SshConnectionEntity> getConnectionList(String userId);

    /**
     * 获取连接的高级配置
     */
    SshConnectionConfigEntity getConnectionConfig(String connectionId);

    /**
     * 建立SSH连接
     * @param connectionId 连接ID
     * @return 是否连接成功
     */
    boolean connect(String connectionId);

    /**
     * 断开SSH连接
     * @param connectionId 连接ID
     */
    void disconnect(String connectionId);

    /**
     * 检查连接是否活跃
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    boolean isConnected(String connectionId);
}
