package com.llf.ai.domain.agent.service.context.provider.impl;

import com.llf.ai.domain.agent.model.valobj.intent.TaskStateVO;
import com.llf.ai.domain.agent.service.IIntentService;
import com.llf.ai.domain.agent.service.context.provider.ContextProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文提供者（order=20）
 * <p>
 * 功能：从消息历史中提取首条 user 消息作为"当前任务描述"，
 * 让模型在长对话、多轮工具调用后仍能记住最初的目标（防"任务漂移" - 面试考点）。
 * <p>
 * 运行过程：
 * <pre>
 *   messageHistory（时间正序）
 *   +----------------------------------------------------+
 *   | [0] user:      "帮我排查 nginx 502"   <--+ 初始目标 |
 *   | [1] assistant: "好的，先看日志..."         |          |
 *   | [2] tool:      "tail -100 error.log..."  |          |
 *   | [3] assistant: "发现 upstream 超时..."   |          |
 *   | ...（几十轮后，模型容易忘记最初任务）       |          |
 *   +----------------------------------------------------+
 *                     |
 *                     v  TaskProvider.provide()
 *            从前往后找第一条 role=user 的消息
 *                     |
 *                     v
 *        Map{ taskDescription: "帮我排查 nginx 502" }
 *                     |
 *                     v
 *   DynamicPromptBuilder 渲染为消息前缀的 [当前任务] 段落
 *   --> 每轮对话都提醒模型"你最初的任务是什么"
 * </pre>
 * 设计说明：取"首条"而非"最近"——首条用户消息代表会话的初始目标；
 * 后续 user 消息多为补充/纠偏，已由 MilestoneProvider 覆盖。
 *
 * @author llf
 */
@Component
@Slf4j
public class TaskProvider implements ContextProvider {

    @Resource
    private IIntentService intentService;

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String ownerId, String terminalSessionId,
                                       List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // 优先从 TaskStateVO 获取：TaskStateVO 的任务描述是经过意图分类系统"验证"过的，
        // 比从消息历史中推断更准确。分类时如果识别为业务意图，就把当前消息设为任务描述。
        if (intentService != null) {
            TaskStateVO taskState = intentService.getTaskState(sessionId);
            if (taskState != null && taskState.getTaskDescription() != null
                    && !taskState.getTaskDescription().isBlank()) {
                result.put("taskDescription", taskState.getTaskDescription());
                return result;
            }
        }

        // 降级：从消息历史中找第一条 user 消息，并清洗可能带的后缀污染。
        // 这里我们先对可能带后缀的 user message 做一次简单清洗。
        if (messageHistory != null) {
            messageHistory.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .findFirst()
                    .ifPresent(m -> {
                        String content = (String) m.get("content");
                        if (content != null) {
                            result.put("taskDescription", stripDynamicSuffix(content));
                        }
                        log.info("[上下文管理] [当前任务] 已提取: sessionId={}, messageLength={}",
                                sessionId, content.toString().length());
                    });
        }
        return result;
    }

    private String stripDynamicSuffix(String text) {
        if (text == null) return null;
        if (text.contains("\n---\n")) {
            String[] parts = text.split("\\n---\\n", 2);
            return parts.length == 2 ? parts[0].trim() : text;
        }
        return text;
    }
}
