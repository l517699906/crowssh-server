package com.llf.ai.api.dto;

import lombok.Data;

/**
 * 取消正在运行的 AI 流式请求。
 */
@Data
public class ChatStreamCancelRequestDTO {

    private String sessionId;
    private String terminalSessionId;
}
