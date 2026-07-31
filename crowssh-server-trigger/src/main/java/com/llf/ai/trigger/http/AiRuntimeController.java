package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.RuntimeModelConfigDTO;
import com.llf.ai.api.response.Response;
import com.llf.ai.domain.agent.service.model.RuntimeChatModelService;
import com.llf.ai.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/ai/runtime")
@CrossOrigin(origins = "*")
public class AiRuntimeController {

    @Resource
    private RuntimeChatModelService runtimeChatModelService;

    @PostMapping("/test")
    public Response<String> test(@RequestBody RuntimeModelConfigDTO requestDTO) {
        try {
            runtimeChatModelService.test(RuntimeModelConfigMapper.from(requestDTO));
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("连接正常")
                    .data("OK")
                    .build();
        } catch (Exception error) {
            log.warn("客户端 AI 配置连接测试失败: {}", error.getMessage());
            return Response.<String>builder()
                    .code("AI_CONFIG_ERROR")
                    .info("连接失败，请检查服务地址、模型和 API Key")
                    .build();
        } finally {
            requestDTO.setApiKey(null);
        }
    }
}
