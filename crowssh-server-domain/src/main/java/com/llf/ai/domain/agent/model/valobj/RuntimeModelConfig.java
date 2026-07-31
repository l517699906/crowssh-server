package com.llf.ai.domain.agent.model.valobj;

import lombok.Getter;

@Getter
public class RuntimeModelConfig {

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Double temperature;
    private final Integer maxTokens;

    public RuntimeModelConfig(String provider,
                              String baseUrl,
                              String apiKey,
                              String model,
                              Double temperature,
                              Integer maxTokens) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public String toString() {
        return "RuntimeModelConfig{" +
                "provider='" + provider + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" +
                ", model='" + model + '\'' +
                ", temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                '}';
    }
}
