package com.llf.ai.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llf.ai.infrastructure.dao.po.SshConnectionConfigPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * SSH连接高级配置DAO
 *
 * @author llf
 */
@Mapper
public interface ISshConnectionConfigDAO extends BaseMapper<SshConnectionConfigPO> {

    /**
     * 插入或更新SSH连接高级配置
     *
     * @param po 配置对象
     * @return 是否写入成功
     */
    @Override
    @Insert("INSERT INTO ssh_connection_config (" +
            "connection_id, connect_timeout, keepalive_interval, startup_command, " +
            "compression, strict_host_key_check, known_hosts" +
            ") VALUES (" +
            "#{connectionId}, #{connectTimeout}, #{keepaliveInterval}, #{startupCommand}, " +
            "#{compression}, #{strictHostKeyCheck}, #{knownHosts}" +
            ") ON DUPLICATE KEY UPDATE " +
            "connect_timeout = VALUES(connect_timeout), " +
            "keepalive_interval = VALUES(keepalive_interval), " +
            "startup_command = VALUES(startup_command), " +
            "compression = VALUES(compression), " +
            "strict_host_key_check = VALUES(strict_host_key_check), " +
            "known_hosts = VALUES(known_hosts)")
    boolean insertOrUpdate(SshConnectionConfigPO po);

    /**
     * 根据连接ID查询高级配置
     *
     * @param connectionId 连接唯一标识
     * @return 配置对象
     */
    default SshConnectionConfigPO queryByConnectionId(String connectionId) {
        return selectOne(Wrappers.<SshConnectionConfigPO>lambdaQuery()
                .eq(SshConnectionConfigPO::getConnectionId, connectionId));
    }
}
