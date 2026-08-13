package com.llf.ai.domain.ssh.adapter.port;

/**
 * 远端系统不具备 Linux procfs 监控能力。
 */
public class ServerMonitorUnsupportedException extends RuntimeException {

    public ServerMonitorUnsupportedException(String message) {
        super(message);
    }
}
