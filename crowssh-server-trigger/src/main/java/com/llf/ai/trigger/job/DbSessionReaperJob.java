package com.llf.ai.trigger.job;

import com.llf.ai.domain.db.service.IDbSessionReaper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DbSessionReaperJob {
    private final IDbSessionReaper reaper;

    public DbSessionReaperJob(IDbSessionReaper reaper) {
        this.reaper = reaper;
    }

    @Scheduled(fixedDelayString = "${crowssh.db.session.reap-interval-ms:60000}")
    public void reap() {
        int count = reaper.reapIdleSessions();
        if (count > 0) log.info("数据库工作台会话回收完成 count={}", count);
    }
}
