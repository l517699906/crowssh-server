package com.llf.ai.domain.ssh.adapter.port;

/**
 * 监控采集因连接、超时或远端输出异常而暂时不可用。
 */
public class ServerMonitorUnavailableException extends RuntimeException {

    public ServerMonitorUnavailableException(String message) {
        super(message);
    }

    public ServerMonitorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
