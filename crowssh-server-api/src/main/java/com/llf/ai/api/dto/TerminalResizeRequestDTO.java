package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调整终端大小请求
 *
 * @author xiaofuge
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalResizeRequestDTO {

    /** 终端会话ID */
    private String sessionId;

    /** 新的列数 */
    private Integer cols;

    /** 新的行数 */
    private Integer rows;

}
