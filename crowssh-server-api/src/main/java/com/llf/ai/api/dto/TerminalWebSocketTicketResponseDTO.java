package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 终端 WebSocket 一次性短期票据。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalWebSocketTicketResponseDTO {

    private String ticket;

    private long expiresInSeconds;
}
