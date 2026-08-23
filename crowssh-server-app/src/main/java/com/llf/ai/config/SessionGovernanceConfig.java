package com.llf.ai.config;

import com.llf.ai.domain.ssh.config.SessionGovernanceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 终端会话治理配置装配。
 *
 * <p>注册 {@link SessionGovernanceProperties} 为 bean，供领域服务
 * {@code SshTerminalService} 注入（会话配额 / 空闲 TTL），
 * 并供定时任务读取回收周期 {@code crowssh.session.reap-interval-ms}。
 *
 * @author llf
 */
@Configuration
@EnableConfigurationProperties(SessionGovernanceProperties.class)
public class SessionGovernanceConfig {
}
