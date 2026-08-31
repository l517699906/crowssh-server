package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 终端 WebSocket 短期票据申请。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalWebSocketTicketRequestDTO {

    private String sessionId;

    /** 客户端已经确认展示的最后一个服务端输出序号。 */
    private Long resumeAfter;
}
