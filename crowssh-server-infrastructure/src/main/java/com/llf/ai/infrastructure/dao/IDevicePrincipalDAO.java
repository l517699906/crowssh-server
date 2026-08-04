package com.llf.ai.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llf.ai.infrastructure.dao.po.DevicePrincipalPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备身份 DAO。
 */
@Mapper
public interface IDevicePrincipalDAO extends BaseMapper<DevicePrincipalPO> {

    default DevicePrincipalPO queryActiveByTokenHash(String tokenHash) {
        return selectOne(Wrappers.<DevicePrincipalPO>lambdaQuery()
                .eq(DevicePrincipalPO::getTokenHash, tokenHash)
                .eq(DevicePrincipalPO::getStatus, 1));
    }
}
