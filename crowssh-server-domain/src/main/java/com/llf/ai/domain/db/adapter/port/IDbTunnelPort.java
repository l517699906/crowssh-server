package com.llf.ai.domain.db.adapter.port;

public interface IDbTunnelPort {
    TunnelLease acquire(String ownerId, String sshConnectionId, String destinationHost, int destinationPort);

    void release(String leaseId);

    /** 未实现租约存活检测的隧道适配器不能继续承载工作台。 */
    default boolean isActive(String leaseId) { return false; }

    record TunnelLease(String leaseId, String localHost, int localPort) {
    }
}
