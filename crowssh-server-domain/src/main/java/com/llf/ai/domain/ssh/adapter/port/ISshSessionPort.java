package com.llf.ai.domain.ssh.adapter.port;

import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;

/**
 * SSH 会话服务接口
 *
 * @author llf
 */
public interface ISshSessionPort {

    /**
     * 建立 SSH 连接
     *
     * @param connection 连接信息
     * @param config     运行时配置
     * @return 是否连接成功
     */
    boolean connect(SshConnectionEntity connection, SshConnectionConfigEntity config);

    /**
     * 使用表单草稿测试 SSH 连接，不写入正式连接缓存
     *
     * @param connection 连接信息
     * @param config     运行时配置
     */
    void testConnection(SshConnectionEntity connection, SshConnectionConfigEntity config);

    /**
     * 断开 SSH 连接
     *
     * @param connectionId 连接ID
     */
    void disconnect(String connectionId);

    /**
     * 检查是否已连接
     *
     * @param connectionId 连接ID
     * @return 是否已连接
     */
    boolean isConnected(String connectionId);
}
