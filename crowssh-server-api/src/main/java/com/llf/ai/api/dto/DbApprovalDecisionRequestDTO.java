package com.llf.ai.api.dto;

/** DB 审批必须明确当前聊天与轮次；设备身份由认证主体提供。 */
public record DbApprovalDecisionRequestDTO(String sessionId, String turnId, String decision) { }
