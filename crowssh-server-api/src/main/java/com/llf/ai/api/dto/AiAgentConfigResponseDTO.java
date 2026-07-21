package com.llf.ai.api.dto;

import lombok.Data;

/**
 * 智能体配置响应DTO
 *
 * @author llf
 * @date 2026/06/20
 */
@Data
public class AiAgentConfigResponseDTO {

    /**
     * 智能体id
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;
}
