package com.llf.ai.domain.ssh.adapter.port;

/**
 * SSH 目标地址被服务端出站策略阻止。
 */
public class SshTargetBlockedException extends IllegalArgumentException {

    public SshTargetBlockedException(String message) {
        super(message);
    }
}
