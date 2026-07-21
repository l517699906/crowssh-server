package com.llf.ai.domain.agent.service.armory.matter.mcp.client;

import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 Mcp 构建服务
 *
 * @author llf
 * @date 2026/06/13
 */
public interface ToolMcpCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;
}
