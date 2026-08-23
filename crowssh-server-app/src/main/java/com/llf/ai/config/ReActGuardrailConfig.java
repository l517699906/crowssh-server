package com.llf.ai.config;

import com.llf.ai.cases.react.config.ReActProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ReAct 循环护栏配置装配。
 *
 * <p>注册 {@link ReActProperties} 为 bean，供 {@code RootNode}（步数/工具调用/墙钟）与
 * {@code AiCallNode}（token 预算）注入。绑定前缀 {@code crowssh.react}。
 *
 * @author llf
 */
@Configuration
@EnableConfigurationProperties(ReActProperties.class)
public class ReActGuardrailConfig {
}
