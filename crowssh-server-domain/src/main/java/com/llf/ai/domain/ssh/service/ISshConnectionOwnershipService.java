package com.llf.ai.domain.ssh.service;

/**
 * SSH 连接归属校验边界。
 */
@FunctionalInterface
public interface ISshConnectionOwnershipService {

    /**
     * 校验连接是否属于当前设备身份。
     */
    void requireOwnership(String ownerId, String connectionId);
}
