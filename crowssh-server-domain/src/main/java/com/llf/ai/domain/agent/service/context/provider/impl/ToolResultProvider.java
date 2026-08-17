package com.llf.ai.domain.agent.service.context.provider.impl;

import com.llf.ai.domain.agent.service.context.provider.ContextProvider;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 工具结果上下文提供者（order=40）
 * <p>
 * 功能：按会话缓存 ReAct 循环中的工具执行结果，生成"工具执行摘要"注入 Prompt，
 * 让模型在多轮工具调用后仍能全局回顾"之前执行过什么、结果如何"。
 * <p>
 * 运行过程：
 * <pre>
 *   写入路径（ReAct 每轮工具执行后）：
 *   AiCallNode/ToolCallNode --> ChatContextService.pushToolResult()
 *        |
 *        v
 *   pushResult(sessionId, toolName, result)
 *        |
 *        +--> results[sessionId].add(ToolResultEntry)   追加记录
 *        +--> summaryCache.remove(sessionId)            使摘要缓存失效
 *
 *   读取路径（下一轮构建上下文时）：
 *   provide(sessionId, ...)
 *        |
 *        v
 *   results[sessionId] 为空 ? --> 返回空 Map
 *        |
 *        v
 *   summaryCache.computeIfAbsent(sessionId, generateSummary)  懒摘要
 *        |
 *        +-- 条目 <= 5：逐条拼接 "toolName: 结果(截断100字)"
 *        +-- 条目 >  5："最近执行了 N 个工具调用" + 最近5条(截断80字)
 *        |
 *        v
 *   Map{ toolResultSummary } --> 消息前缀 [工具执行摘要] 段落
 * </pre>
 * 缓存设计：摘要是"懒加载"的——provide 时若缓存命中直接返回；
 * 只有 pushResult 写入新结果才使缓存失效，避免每轮重复生成。
 *
 * @author llf
 */
@Component
@Slf4j
public class ToolResultProvider implements ContextProvider {

    private static final int MAX_RESULTS_PER_SESSION = 50;

    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "tool-result";
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String ownerId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) {
            log.info("[上下文管理] [工具执行摘要] 当前无可注入结果: sessionId={}", sessionId);
            return result;
        }

        // 懒摘要：有缓存直接返回，否则重新生成
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        log.info("[上下文管理] [工具执行摘要] 已注入 Prompt: sessionId={}, entryCount={}, summaryLength={}",
                sessionId, entries.size(), summary.length());
        return result;
    }

    public void pushResult(String sessionId, String toolName, String result) {
        List<ToolResultEntry> entries = results.computeIfAbsent(
                sessionId, k -> new CopyOnWriteArrayList<>());
        synchronized (entries) {
            entries.add(new ToolResultEntry(toolName, result));
            while (entries.size() > MAX_RESULTS_PER_SESSION) {
                entries.remove(0);
            }
        }
        summaryCache.remove(sessionId);  // 失效摘要缓存
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        results.remove(sessionId);
        summaryCache.remove(sessionId);
    }

    private String generateSummary(List<ToolResultEntry> entries) {
        // 少量结果直接拼接，大量结果模板化压缩
        if (entries.size() <= 5) {
            return entries.stream()
                    .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                    .collect(Collectors.joining("\n"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近执行了 ").append(entries.size()).append(" 个工具调用:\n");
        // 只取最近 5 条详细 + 总结
        List<ToolResultEntry> recent = entries.subList(entries.size() - 5, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
                    .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }

}
