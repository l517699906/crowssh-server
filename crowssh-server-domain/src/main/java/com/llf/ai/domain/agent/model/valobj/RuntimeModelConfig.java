package com.llf.ai.domain.agent.model.valobj;

import lombok.Getter;

@Getter
public class RuntimeModelConfig {

    private final String provider;
    private final String protocol;
    private final String baseUrl;
    private final String apiKey;
    private final String authType;
    private final String authHeader;
    private final String authPrefix;
    private final String modelListPath;
    private final String model;
    private final Double temperature;
    private final Boolean omitTemperature;
    private final String tokenParameter;
    private final Integer maxTokens;

    public RuntimeModelConfig(String provider,
                              String protocol,
                              String baseUrl,
                              String apiKey,
                              String authType,
                              String authHeader,
                              String authPrefix,
                              String modelListPath,
                              String model,
                              Double temperature,
                              Boolean omitTemperature,
                              String tokenParameter,
                              Integer maxTokens) {
        this.provider = provider;
        this.protocol = protocol;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.authType = authType;
        this.authHeader = authHeader;
        this.authPrefix = authPrefix;
        this.modelListPath = modelListPath;
        this.model = model;
        this.temperature = temperature;
        this.omitTemperature = omitTemperature;
        this.tokenParameter = tokenParameter;
        this.maxTokens = maxTokens;
    }

    @Override
    public String toString() {
        return "RuntimeModelConfig{" +
                "provider='" + provider + '\'' +
                ", protocol='" + protocol + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" +
                ", authType='" + authType + '\'' +
                ", authHeader='" + authHeader + '\'' +
                ", modelListPath='" + modelListPath + '\'' +
                ", model='" + model + '\'' +
                ", temperature=" + temperature +
                ", omitTemperature=" + omitTemperature +
                ", tokenParameter='" + tokenParameter + '\'' +
                ", maxTokens=" + maxTokens +
                '}';
    }
}
