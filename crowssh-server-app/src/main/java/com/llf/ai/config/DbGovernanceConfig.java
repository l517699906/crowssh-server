package com.llf.ai.config;

import com.llf.ai.domain.db.config.DbSessionGovernanceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DbSessionGovernanceProperties.class)
public class DbGovernanceConfig {
    @org.springframework.context.annotation.Bean(name = "dbConsoleExecutor", destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService dbConsoleExecutor() {
        // 无排队通道：容量耗尽立即拒绝，避免 SQL 在不确定时间后启动。
        return new java.util.concurrent.ThreadPoolExecutor(0, 50, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "db-console");
                    thread.setDaemon(true);
                    return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
}
