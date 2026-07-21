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
