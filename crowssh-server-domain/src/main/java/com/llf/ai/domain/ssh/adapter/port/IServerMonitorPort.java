package com.llf.ai.domain.ssh.adapter.port;

import com.llf.ai.domain.ssh.model.entity.ServerMonitorSnapshotEntity;

/**
 * 远端服务器监控采集端口。
 */
public interface IServerMonitorPort {

    ServerMonitorSnapshotEntity collectSnapshot(String connectionId);
}
