package com.llf.ai.domain.agent.service.context;

import com.llf.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.llf.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.llf.ai.domain.agent.service.IChatContextService;
import com.llf.ai.domain.agent.service.context.provider.ContextProvider;
import com.llf.ai.domain.agent.service.context.reducer.impl.HybridReducer;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.llf.ai.domain.agent.service.context.provider.impl.ToolResultProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * 上下文管理领域服务（context 包的聚合核心）
 * <p>
 * 功能：ReAct 对话的"上下文中枢"，对上（PromptService/Case层）提供三个能力：
 * <pre>
 *   buildPromptContext()  聚合所有 Provider 的输出 --> PromptContextVO
 *   trimHistory()         在 token 预算内裁剪消息历史（默认 8000）
 *   pushToolResult()      接收工具执行结果，供生成工具摘要
 * </pre>
 * 整体运行过程（一次 ReAct 循环中的调用时序）：
 * <pre>
 *   AiCallNode.doApply()
 *        |
 *        | (1) trimHistory(history, 8000)
 *        |        |
 *        |        v
 *        |     HybridReducer = 两类候选完整消息组 + 统一 token 预算
 *        |        |
 *        |        v
 *        |     裁剪后的历史回写 DynamicContext
 *        |
 *        | (2) PromptService.buildEnrichedMessage(...)
 *        |        |
 *        |        v
 *        |     buildPromptContext(sessionId, ownerId, terminalSessionId, history)
 *        |        |
 *        |        +--> for provider in providers(按order排序, 跳过disabled):
 *        |        |        TerminalState(10)  {osInfo, currentUser, currentDirectory, uptime}
 *        |        |        Task(20)           {taskDescription}
 *        |        |        Milestone(30)      {milestoneVOS}
 *        |        |        ToolResult(40)     {toolResultSummary}
 *        |        |     finalCtx.putAll(...)   合并所有键值对
 *        |        |
 *        |        v
 *        |     PromptContextVO --> DynamicPromptBuilder --> 消息前缀
 *        |
 *        | (3) 工具执行后 pushToolResult(sessionId, toolName, result)
 *        |        |
 *        |        v
 *        |     ToolResultProvider.pushResult() 缓存 + 使摘要失效
 *        |     （下一轮 (2) 时新摘要进入 Prompt）
 * </pre>
 * 装配机制：@Resource List<ContextProvider> 由 Spring 按类型自动收集
 * 全部 Provider 实现，@PostConstruct 时按 getOrder() 排序——新增 Provider
 * 只需加 @Component，无需修改本类。
 *
 * @author llf
 */
@Service
@Slf4j
public class ChatContextService implements IChatContextService {

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    private final List<ContextProvider> providers;

    @Resource
    private HybridReducer hybridReducer;

    @Resource
    private ToolResultProvider toolResultProvider;

    // 构造函数注入；[Spring Dependency Injection - 依赖注入使用技巧](https://bugstack.cn/md/road-map/spring-dependency-injection.html)
    public ChatContextService(List<ContextProvider> providers) {
        this.providers = providers;
        this.providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    /**
     * 启动验证点：该日志出现即表示当前运行制品已经包含上下文管理实现。
     */
    @PostConstruct
    public void logContextManagementReady() {
        log.info("[上下文管理] 组件已加载: providerCount={}, providers={}",
                providers.size(), providers.stream().map(ContextProvider::getName).toList());
    }

    /**
     * 聚合所有已启用的 ContextProvider，构建最终给 PromptService 使用的 PromptContextVO。
     *
     * <p>执行过程：
     * <pre>
     *   1. Spring 已经把全部 ContextProvider 注入到 providers
     *   2. 构造函数中已按 getOrder() 从小到大排序
     *   3. 这里依次调用 provider.provide(...)
     *   4. 每个 provider 返回一个 Map，上下文字段通过 putAll 合并到 finalCtx
     *   5. 最后把 finalCtx 映射为 PromptContextVO
     * </pre>
     *
     * <p>案例：
     * <pre>
     *   当前 providers 返回结果：
     *
     *   TerminalStateProvider ->
     *   {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx
     *   }
     *
     *   TaskProvider ->
     *   {
     *     taskDescription=排查 nginx 502 错误
     *   }
     *
     *   MilestoneProvider ->
     *   {
     *     milestoneVOS=[用户要求查看 error.log, 工具执行出现 permission denied]
     *   }
     *
     *   ToolResultProvider ->
     *   {
     *     toolResultSummary=最近一次 tail error.log 输出包含 connect() failed
     *   }
     *
     *   最终聚合结果：
     *   PromptContextVO {
     *     osInfo=Linux ubuntu 22.04,
     *     currentUser=root,
     *     currentDirectory=/var/log/nginx,
     *     taskDescription=排查 nginx 502 错误,
     *     milestoneVOS=[...],
     *     toolResultSummary=最近一次 tail error.log 输出包含 connect() failed
     *   }
     * </pre>
     *
     * <p>这样 PromptService 后面就不需要再分别调用多个 provider，
     * 它只需要消费一个聚合好的 PromptContextVO 即可。
     *
     * @param sessionId 当前会话 ID
     * @param terminalSessionId 终端会话 ID
     * @param messageHistory 当前业务侧消息历史
     * @return 聚合完成的 PromptContextVO
     */
    @Override
    @SuppressWarnings("unchecked")
    public PromptContextVO buildPromptContext(String sessionId, String ownerId, String terminalSessionId,
                                              List<Map<String, Object>> messageHistory) {
        Map<String, Object> finalCtx = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            Map<String, Object> ctx = provider.provide(sessionId, ownerId, terminalSessionId, messageHistory);
            if (ctx != null) {
                finalCtx.putAll(ctx);
            }
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .uptime((String) finalCtx.get("uptime"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                .toolResultSummary((String) finalCtx.get("toolResultSummary"))
                // 长期记忆摘要 ：由 LongTermMemoryProvider(order=25) 召回并注入，
                // 经 DynamicPromptBuilder 渲染为 [长期记忆] 段落拼到用户消息前面。
                .longTermMemorySummary((String) finalCtx.get("longTermMemorySummary"))
                .taskDescription((String) finalCtx.get("taskDescription"))
                .build();
    }

    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        // 混合裁剪
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }

    @Override
    public void pushToolResult(String sessionId, String toolName, String result) {
        toolResultProvider.pushResult(sessionId, toolName, result);
        log.info("[上下文管理] [工具执行摘要] 写入缓存: sessionId={}, toolName={}, resultLength={}",
                sessionId, toolName, result == null ? 0 : result.length());
    }

    @Override
    public void clearSessionContext(String sessionId) {
        toolResultProvider.clear(sessionId);
        log.info("[上下文管理] 已清理失效会话缓存: sessionId={}", sessionId);
    }

}
