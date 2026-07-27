package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打开终端会话请求
 *
 * @author xiaofuge
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalOpenRequestDTO {

    /** SSH连接ID */
    private String connectionId;

    /** 终端列数 */
    private Integer cols;

    /** 终端行数 */
    private Integer rows;

}
