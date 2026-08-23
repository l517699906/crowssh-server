package com.llf.ai.domain.ssh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 终端会话治理配置。
 *
 * <p>集中管理会话资源的三条护栏：空闲 TTL、回收扫描周期、单用户会话配额。
 * 采用原始 {@code long}/{@code int} 而非 {@code Duration}，以便同一值可直接喂给
 * {@code @Scheduled(fixedDelayString=...)}。
 *
 * @author llf
 */
@Data
@ConfigurationProperties(prefix = "crowssh.session")
public class SessionGovernanceProperties {

    /** 会话空闲超时（毫秒），超过该时长未活跃的会话将被回收。默认 30 分钟。 */
    private long idleTimeoutMillis = 1_800_000L;

    /** 回收任务扫描周期（毫秒）。默认 1 分钟。 */
    private long reapIntervalMs = 60_000L;

    /** 单个设备身份允许同时持有的最大终端会话数。默认 10。 */
    private int maxSessionsPerOwner = 10;
}
