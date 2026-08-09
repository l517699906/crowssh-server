package com.llf.ai.domain.agent.model.valobj.prompt;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PromptContextVO {

    private String serverInfo;
    private String osInfo;
    private String currentUser;
    private String currentDirectory;
    private String uptime;

    /**
     * 执行命令
     */
    private List<String> recentCommands;

    /**
     * 里程碑记录
     */
    private List<MilestoneVO> milestoneVOS;

    /**
     * 工具执行摘要
     */
    private String toolResultSummary;

    /**
     * 当前任务描述（首条用户消息）
     */
    private String taskDescription;
}
