package com.llf.ai.domain.ssh.service;

/**
 * 终端会话回收边界。
 *
 * <p>窄接口，仅暴露空闲会话回收能力，供调度适配层（trigger）依赖，
 * 避免调度器耦合到胖接口 {@code ISshTerminalService}。
 *
 * @author llf
 */
@FunctionalInterface
public interface ITerminalSessionReaper {

    /**
     * 回收空闲超时的终端会话。
     *
     * @return 本次回收的会话数
     */
    int reapIdleSessions();
}
