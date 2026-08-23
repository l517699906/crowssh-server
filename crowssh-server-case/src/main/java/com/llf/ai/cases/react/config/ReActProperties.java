package com.llf.ai.cases.react.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ReAct 循环护栏配置。
 *
 * <p>集中承载此前散落在 {@code RootNode}（步数/工具调用上限）与 {@code AiCallNode}
 * （token 预算硬编码 8000）中的护栏参数，并新增墙钟超时兜底，防止模型卡住时长时间占用
 * 后台线程与 SSH 通道。绑定前缀 {@code crowssh.react}。
 *
 * @author llf
 */
@Data
@ConfigurationProperties(prefix = "crowssh.react")
public class ReActProperties {

    /** 最大步数。 */
    private int maxSteps = 50;

    /** 最大工具调用次数（总计）。 */
    private int maxToolCalls = 200;

    /** 每轮最大工具调用次数。 */
    private int maxToolCallsPerRound = 10;

    /** 消息历史裁剪的 token 预算。 */
    private int tokenBudget = 8000;

    /** 整个 ReAct 循环的墙钟超时（毫秒），<=0 表示不启用。 */
    private long wallClockTimeoutMillis = 600_000L;
}
