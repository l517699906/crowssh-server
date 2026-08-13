package com.llf.ai.domain.ssh.service.monitor;

import com.llf.ai.domain.ssh.adapter.port.IServerMonitorPort;
import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ServerMonitorUnavailableException;
import com.llf.ai.domain.ssh.model.entity.ServerMonitorSnapshotEntity;
import com.llf.ai.domain.ssh.service.IServerMonitorService;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import org.springframework.stereotype.Service;

/**
 * 服务器监控领域服务。
 */
@Service
public class ServerMonitorService implements IServerMonitorService {

    private final ISshSessionPort sshSessionPort;
    private final IServerMonitorPort serverMonitorPort;
    private final ISshConnectionOwnershipService ownershipService;

    public ServerMonitorService(
            ISshSessionPort sshSessionPort,
            IServerMonitorPort serverMonitorPort,
            ISshConnectionOwnershipService ownershipService) {
        this.sshSessionPort = sshSessionPort;
        this.serverMonitorPort = serverMonitorPort;
        this.ownershipService = ownershipService;
    }

    @Override
    public ServerMonitorSnapshotEntity getSnapshot(String ownerId, String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        ownershipService.requireOwnership(ownerId, connectionId);
        if (!sshSessionPort.isConnected(connectionId)) {
            throw new ServerMonitorUnavailableException("SSH 连接未建立或已断开");
        }
        return serverMonitorPort.collectSnapshot(connectionId);
    }
}
