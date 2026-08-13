package com.llf.ai.domain.ssh.service;

import com.llf.ai.domain.ssh.model.entity.ServerMonitorSnapshotEntity;

/**
 * 当前设备身份下的服务器监控服务。
 */
public interface IServerMonitorService {

    ServerMonitorSnapshotEntity getSnapshot(String ownerId, String connectionId);
}
