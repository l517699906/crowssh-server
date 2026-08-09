package com.llf.ai.domain.agent.service.context.provider;

import java.util.List;
import java.util.Map;

/**
 * 上下文采集器，提供者接口；SSH环境、初始任务、关键事件、工具摘要
 * <p>
 * 功能：可插拔的上下文采集组件。每个实现负责采集一类上下文
 * （终端环境/当前任务/里程碑/工具结果），由 ChatContextService
 * 按 getOrder() 顺序聚合。新增上下文来源只需新增实现类并加 @Component，
 * 无需改动任何调用方（开闭原则）。
 * <p>
 * 体系架构：
 * <pre>
 *                    ChatContextService
 *                    （注入 List<ContextProvider>，@PostConstruct 按 order 排序）
 *                          |
 *   +----------+-----------+-----------+----------+
 *   |          |           |           |
 *   v          v           v           v
 * Terminal   Task     Milestone  ToolResult     （order: 10/20/30/40）
 * State      Provider Provider   Provider
 * (SSH环境)  (初始任务) (关键事件)  (工具摘要)
 *   |          |           |           |
 *   +----------+-----------+-----------+
 *                          |
 *                          v
 *              合并 Map --> PromptContextVO
 * </pre>
 *
 * @author llf
 */
public interface ContextProvider {

    /** 提供者名称 */
    String getName();

    /** 执行顺序（小的先执行） */
    int getOrder();

    /** 是否启用 */
    boolean enabled();

    /**
     * 采集上下文
     *
     * @param sessionId         对话会话 ID
     * @param ownerId           服务端认证后的资源归属 ID
     * @param terminalSessionId SSH 终端会话 ID（可为 null）
     * @param messageHistory    消息历史
     * @return 上下文键值对（如 osInfo、toolResultSummary），允许为空
     */
    Map<String, Object> provide(String sessionId, String ownerId, String terminalSessionId,
                                List<Map<String, Object>> messageHistory);
}
