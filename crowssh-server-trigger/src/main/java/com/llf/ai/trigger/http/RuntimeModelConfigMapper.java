package com.llf.ai.trigger.http;

import com.llf.ai.api.dto.RuntimeModelConfigDTO;
import com.llf.ai.api.dto.RuntimeModelListRequestDTO;
import com.llf.ai.domain.agent.model.valobj.RuntimeModelConfig;

final class RuntimeModelConfigMapper {

    private RuntimeModelConfigMapper() {
    }

    static RuntimeModelConfig from(RuntimeModelConfigDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请先在客户端配置 AI 模型和 API Key");
        }
        return new RuntimeModelConfig(
                dto.getProvider(),
                dto.getProtocol(),
                dto.getBaseUrl(),
                dto.getApiKey(),
                dto.getAuthType(),
                dto.getAuthHeader(),
                dto.getAuthPrefix(),
                dto.getModelListPath(),
                dto.getModel(),
                dto.getTemperature(),
                dto.getOmitTemperature(),
                dto.getTokenParameter(),
                dto.getMaxTokens()
        );
    }

    static RuntimeModelConfig from(RuntimeModelListRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("请先在客户端配置 AI 服务商和 API Key");
        }
        return new RuntimeModelConfig(
                dto.getProvider(),
                dto.getProtocol(),
                dto.getBaseUrl(),
                dto.getApiKey(),
                dto.getAuthType(),
                dto.getAuthHeader(),
                dto.getAuthPrefix(),
                dto.getModelListPath(),
                null,
                null,
                null,
                null,
                null
        );
    }
}
