package com.llf.ai.domain.agent.service.armory.matter.mcp.client.factory;

import com.llf.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.ToolMcpCreateService;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.impl.SSEToolMcpCreateService;
import com.llf.ai.domain.agent.service.armory.matter.mcp.client.impl.StdioToolMcpCreateService;
import com.llf.ai.types.enums.ResponseCode;
import com.llf.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DefaultMcpClientFactory {

    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;
    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;
    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    public ToolMcpCreateService getToolMcpCreateService(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) {
        if (toolMcp == null) {
            throw new IllegalArgumentException("MCP 工具配置不能为空");
        }

        int transportCount = (toolMcp.getLocal() == null ? 0 : 1)
                + (toolMcp.getSse() == null ? 0 : 1)
                + (toolMcp.getStdio() == null ? 0 : 1);
        if (transportCount > 1) {
            throw new IllegalArgumentException("每个 MCP 工具只能配置一种传输方式: local、sse 或 stdio");
        }

        if (toolMcp.getLocal() != null) {
            return localToolMcpCreateService;
        }
        if (toolMcp.getSse() != null) {
            return sseToolMcpCreateService;
        }
        if (toolMcp.getStdio() != null) {
            return stdioToolMcpCreateService;
        }
        throw new AppException(ResponseCode.NOT_FOUND_METHOD.getCode(), ResponseCode.NOT_FOUND_METHOD.getInfo());
    }
}
