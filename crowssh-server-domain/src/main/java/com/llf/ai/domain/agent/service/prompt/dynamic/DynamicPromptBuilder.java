package com.llf.ai.domain.agent.service.prompt.dynamic;

import com.llf.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.llf.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态 Prompt 构建器
 * <p>
 * 负责将 {@link PromptContextVO} 中的环境信息、最近命令、里程碑事件
 * 翻译成模型可读的结构化文本，提供两种构建方式：
 * <ul>
 *   <li>{@link #build} —— 追加到 system instruction 末尾</li>
 *   <li>{@link #buildMessageSuffix} —— 构建为用户消息前缀（当前使用）</li>
 * </ul>
 *
 * @author llf
 * 2026/7/30 23:16
 */
@Slf4j
@Component
public class DynamicPromptBuilder {

    /**
     * 将动态上下文追加到基础指令后面，拼成完整的 system instruction。
     * <p>
     * 适用于运行期可修改 system instruction 的场景。
     *
     * @param baseInstruction 基础系统指令文本
     * @param ctx             动态上下文，为 null 时直接返回基础指令
     * @return 拼接了环境信息、最近命令、里程碑事件的完整指令
     */
    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendTaskDescription(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendToolResultSummary(sb, ctx);
        appendLongTermMemorySummary(sb, ctx);
        appendIntentLabel(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    /**
     * 将动态上下文构建为用户消息后缀（注入到用户消息后面，缓存友好）。
     * <p>
     * 适用于无法直接修改 system instruction 的场景——ADK Runner 的 system instruction
     * 在 Agent 装配阶段就固定了，运行期改不了，因此把动态上下文拼在用户消息后面（保证前缀稳定，利于 Prompt Cache）。
     * <p>
     * 三类上下文"有才拼、没有不拼"：第一轮对话无历史时返回空串，不塞空标题浪费 token。
     * <p>
     * 案例 1：有环境信息和里程碑
     * <pre>
     *   ctx = PromptContextVO {
     *     serverInfo="192.168.1.100",
     *     osInfo="Linux 5.15.0",
     *     currentUser="root",
     *     currentDirectory="/var/log/nginx",
     *     milestoneVOS=[{type=ERROR, content="permission denied"}],
     *     intentLabel="DIAGNOSE"
     *   }
     *
     *   返回：
     *   "[系统环境]\n服务器: 192.168.1.100\n系统: Linux 5.15.0\n用户: root\n目录: /var/log/nginx\n\n[关键事件]\n- [ERROR] permission denied\n\n[用户意图]\nDIAGNOSE\n"
     * </pre>
     * <p>
     * 案例 2：无动态信息（返回空串）
     * <pre>
     *   ctx = PromptContextVO {}  // 所有字段为空
     *
     *   返回：""  // 不返回空标题，避免浪费 token
     * </pre>
     * <p>
     * 缓存友好设计：动态上下文放在用户消息末尾，保证 system instruction + 历史消息 +
     * 用户原始消息 这个前缀序列逐字节稳定，使 LLM Prompt Cache 能命中前面的稳定部分。
     */
    public String buildMessageSuffix(PromptContextVO ctx) {
        if (ctx == null) return "";

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (!isEmpty(ctx.getServerInfo()) || !isEmpty(ctx.getOsInfo())
                || !isEmpty(ctx.getCurrentUser()) || !isEmpty(ctx.getCurrentDirectory())
                || !isEmpty(ctx.getUptime())) {
            sb.append("[系统环境]\n");
            if (!isEmpty(ctx.getServerInfo()))       sb.append("服务器: ").append(ctx.getServerInfo()).append("\n");
            if (!isEmpty(ctx.getOsInfo()))           sb.append("系统: ").append(ctx.getOsInfo()).append("\n");
            if (!isEmpty(ctx.getCurrentUser()))      sb.append("用户: ").append(ctx.getCurrentUser()).append("\n");
            if (!isEmpty(ctx.getCurrentDirectory())) sb.append("目录: ").append(ctx.getCurrentDirectory()).append("\n");
            if (!isEmpty(ctx.getUptime()))           sb.append("运行时长: ").append(ctx.getUptime()).append("\n");
            hasContent = true;
        }

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            sb.append("\n[最近执行的命令]\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        if (ctx.getMilestoneVOS() != null && !ctx.getMilestoneVOS().isEmpty()) {
            sb.append("\n[关键事件]\n");
            for (MilestoneVO m : ctx.getMilestoneVOS()) {
                sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
            }
            hasContent = true;
        }

        if (!isEmpty(ctx.getTaskDescription())) {
            sb.append("\n[当前任务]\n").append(ctx.getTaskDescription()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getToolResultSummary())) {
            sb.append("\n[工具执行摘要]\n").append(ctx.getToolResultSummary()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getLongTermMemorySummary())) {
            sb.append("\n[长期记忆]\n").append(ctx.getLongTermMemorySummary()).append("\n");
            hasContent = true;
        }

        if (!isEmpty(ctx.getIntentLabel())) {
            log.info("意图识别:{}", ctx.getIntentLabel());
            sb.append("\n[用户意图]\n").append(ctx.getIntentLabel()).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";

        String suffix = sb.toString();
        log.debug("构建消息后缀，长度: {}", suffix.length());
        return suffix;
    }

    /**
     * 追加当前任务描述段落（优先从 TaskStateVO 获取，比从消息历史推断更准确）
     */
    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) {
            return;
        }
        sb.append("\n\n## 当前任务\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    /**
     * 追加工具执行摘要段落
     */
    private void appendToolResultSummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getToolResultSummary())) {
            return;
        }
        sb.append("\n\n## 工具执行摘要\n");
        sb.append(ctx.getToolResultSummary()).append("\n");
    }

    /**
     * 追加长期记忆段落（2-8 新增）。
     * <p>
     * 将 LongTermMemoryProvider 召回的长期记忆摘要渲染为 [长期记忆] 段落，
     * 拼到用户消息后面，让主模型感知用户偏好、环境信息、软件版本、排查经验等。
     */
    private void appendLongTermMemorySummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getLongTermMemorySummary())) {
            return;
        }
        sb.append("\n\n## 长期记忆\n");
        sb.append(ctx.getLongTermMemorySummary()).append("\n");
    }

    /**
     * 追加用户意图标签段落
     */
    private void appendIntentLabel(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getIntentLabel())) {
            return;
        }
        sb.append("\n\n## 用户意图\n");
        sb.append(ctx.getIntentLabel()).append("\n");
    }


    /**
     * 将环境信息以 Markdown 格式追加到 StringBuilder 中。
     * <p>
     * 服务器、操作系统、当前用户、工作目录和运行时长全为空时跳过，不输出空标题。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getServerInfo()) && isEmpty(ctx.getOsInfo())
                && isEmpty(ctx.getCurrentUser()) && isEmpty(ctx.getCurrentDirectory())
                && isEmpty(ctx.getUptime())) {
            return;
        }
        sb.append("\n\n## 当前环境信息\n");
        if (!isEmpty(ctx.getServerInfo()))       sb.append("- 服务器: ").append(ctx.getServerInfo()).append("\n");
        if (!isEmpty(ctx.getOsInfo()))           sb.append("- 操作系统: ").append(ctx.getOsInfo()).append("\n");
        if (!isEmpty(ctx.getCurrentUser()))      sb.append("- 当前用户: ").append(ctx.getCurrentUser()).append("\n");
        if (!isEmpty(ctx.getCurrentDirectory())) sb.append("- 工作目录: ").append(ctx.getCurrentDirectory()).append("\n");
        if (!isEmpty(ctx.getUptime()))           sb.append("- 运行时长: ").append(ctx.getUptime()).append("\n");
    }

    /**
     * 将最近执行的命令列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 命令列表为 null 或空时跳过。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    /**
     * 将里程碑事件列表以 Markdown 列表格式追加到 StringBuilder 中。
     * <p>
     * 每条里程碑格式为：{@code [TYPE] content}
     * <p>
     * 案例：
     * <pre>
     *   ctx.getMilestoneVOS() = [
     *     { type=ERROR, content="permission denied" },
     *     { type=TASK_CHANGE, content="换个思路看 access.log" }
     *   ]
     *
     *   追加结果：
     *   "\n## 关键事件\n- [ERROR] permission denied\n- [TASK_CHANGE] 换个思路看 access.log\n"
     * </pre>
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param s 待检查字符串
     * @return true 表示为 null 或全空白
     */
    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
