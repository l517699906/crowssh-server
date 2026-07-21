package com.llf.ai.domain.agent.model.entity;

import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令
 *
 * @author llf
 * @date 2026/05/30
 */

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {

    private AiAgentConfigTableVO aiAgentConfigTableVO;
}
