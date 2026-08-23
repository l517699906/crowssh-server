package com.llf.ai.trigger.job;

import com.llf.ai.domain.ssh.service.ITerminalSessionReaper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 终端会话回收定时任务。
 *
 * <p>调度适配层：按配置周期触发领域侧的空闲会话回收，自身不承载回收逻辑。
 * 依赖窄接口 {@link ITerminalSessionReaper}，与 {@code ISshTerminalService} 解耦。
 *
 * @author llf
 */
@Slf4j
@Component
public class TerminalSessionReaperJob {

    private final ITerminalSessionReaper reaper;

    public TerminalSessionReaperJob(ITerminalSessionReaper reaper) {
        this.reaper = reaper;
    }

    /**
     * 周期性回收空闲终端会话。周期由 {@code crowssh.session.reap-interval-ms} 配置，默认 1 分钟。
     */
    @Scheduled(fixedDelayString = "${crowssh.session.reap-interval-ms:60000}")
    public void reap() {
        int reaped = reaper.reapIdleSessions();
        if (reaped > 0) {
            log.info("终端会话回收任务完成，回收 {} 个空闲会话", reaped);
        }
    }
}
