package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 终端写入请求（原始输入，逐字节发送到 Shell）
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalWriteRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 输入内容（原始按键数据） */
    private String input;

}
