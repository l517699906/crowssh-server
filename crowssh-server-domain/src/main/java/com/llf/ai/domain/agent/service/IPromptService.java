package com.llf.ai.domain.agent.service;

import java.util.List;

/**
 * 提示词领域服务接口
 * <p>
 * 封装动态 Prompt 构建、里程碑追踪、环境信息采集等能力，
 * 使 case 层仅依赖此接口，不直接接触 DynamicPromptBuilder / MilestoneTracker 等内部组件。
 *
 * @author llf
 * 2026/7/30 23:16
 */
public interface IPromptService {

    /**
     * 检测并记录里程碑事件（用户纠偏、任务切换、错误等）
     *
     * @param sessionId 对话会话 ID
     * @param role      消息角色："user" 或 "tool"
     * @param content   消息内容
     */
    void detectAndRecordMilestone(String sessionId, String role, String content);

    /**
     * 构建注入了动态上下文的用户消息
     * <p>
     * 内部完成以下工作：
     * <br/>1. 从 SSH 终端采集环境信息（OS、用户、工作目录）
     * <br/>2. 获取最近里程碑事件
     * <br/>3. 构建 PromptContextVO 并生成消息前缀
     * <br/>4. 将前缀与原始用户消息拼接
     *
     * @param userMessage        原始用户消息
     * @param sessionId          对话会话 ID
     * @param terminalSessionId  SSH 终端会话 ID（可为 null）
     * @param recentCommands     最近执行的命令列表
     * @return 注入了动态上下文的用户消息
     */
    String buildEnrichedMessage(String userMessage, String sessionId, String terminalSessionId, List<String> recentCommands);

    /**
     * 清除指定会话的里程碑记录
     *
     * @param sessionId 对话会话 ID
     */
    void clearMilestones(String sessionId);

}
