package com.llf.ai.domain.agent.service.prompt;

import com.llf.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.llf.ai.domain.agent.service.IChatContextService;
import com.llf.ai.domain.agent.service.IPromptService;
import com.llf.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 提示词服务
 * <p>
 * 组合 DynamicPromptBuilder、MilestoneTracker、IChatContextService，
 * 向 case 层提供统一的提示词领域能力。
 * <p>
 * 上下文采集（环境/任务/里程碑/工具摘要）已下沉到 IChatContextService 的
 * Provider 体系，本类只负责组装前缀并拼接到用户消息。
 *
 * @author llf
 * 2026/7/30 23:16
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    /** 动态提示词构建器——负责组装结构化消息前缀（环境/命令/里程碑/工具摘要/任务） */
    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    /** 里程碑追踪器——检测并缓存用户纠偏、任务切换等关键事件，供动态 Prompt 引用 */
    @Resource
    private MilestoneTracker milestoneTracker;

    /** 上下文管理服务——聚合各 ContextProvider 输出，组装 PromptContextVO */
    @Resource
    private IChatContextService chatContextService;

    /**
     * 检测并记录里程碑事件（用户纠偏、任务切换、错误等）。
     * <p>
     * 委托 {@link MilestoneTracker#detectAndRecord} 完成实际识别和缓存。
     *
     * @param sessionId 对话会话 ID
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容
     */
    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    /**
     * 构建注入了动态上下文的用户消息（富化消息）。
     * <p>
     * 内部完成两步：
     * <ol>
     *   <li>通过 {@link IChatContextService#buildPromptContext} 聚合上下文（终端环境、当前任务、里程碑、工具摘要）</li>
     *   <li>调用 {@link DynamicPromptBuilder#buildMessagePrefix} 生成结构化前缀，拼在原始消息前面</li>
     * </ol>
     * 前缀为空（第一轮无历史）时直接返回原始用户消息。
     *
     * @param userMessage        原始用户消息
     * @param ownerId            服务端认证后的资源归属 ID
     * @param sessionId          对话会话 ID
     * @param terminalSessionId  SSH 终端会话 ID（可为 null）
     * @param recentCommands     最近执行的命令列表
     * @param messageHistory     对话历史记录
     * @return 注入了动态上下文的用户消息
     */
    @Override
    public String buildEnrichedMessage(String userMessage, String ownerId, String sessionId, String terminalSessionId,
                                       List<String> recentCommands, List<Map<String, Object>> messageHistory) {
        // 1. 通过 ChatContextService 采集上下文
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(
                sessionId, ownerId, terminalSessionId, messageHistory);

        // 追加来自 Case 层的 recentCommands
        promptContextVO.setRecentCommands(recentCommands);

        // 2. 生成消息前缀
        String prefix = dynamicPromptBuilder.buildMessagePrefix(promptContextVO);
        String enrichedMessage = prefix.isEmpty()
                ? userMessage
                : prefix + "\n---\n" + userMessage;

        // 日志验证点：只记录上下文段是否注入及长度，不记录命令输出或完整 Prompt。
        log.info("[上下文管理] Prompt 注入: sessionId={}, ownerPresent={}, terminalPresent={}, "
                        + "[当前任务]={}, [工具执行摘要]={}, historySize={}, prefixLength={}, enrichedLength={}",
                sessionId,
                hasText(ownerId),
                hasText(terminalSessionId),
                hasText(promptContextVO.getTaskDescription()),
                hasText(promptContextVO.getToolResultSummary()),
                messageHistory == null ? 0 : messageHistory.size(),
                prefix.length(),
                enrichedMessage == null ? 0 : enrichedMessage.length());

        return enrichedMessage;
    }

    /**
     * 清除指定会话的里程碑记录。
     * <p>
     * 委托 {@link MilestoneTracker#clear} 完成。
     *
     * @param sessionId 对话会话 ID
     */
    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
