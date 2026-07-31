package com.llf.ai.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuntimeModelConfigDTO {

    private String provider;

    private String baseUrl;

    private String apiKey;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    @Override
    public String toString() {
        return "RuntimeModelConfigDTO{" +
                "provider='" + provider + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" +
                ", model='" + model + '\'' +
                ", temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                '}';
    }
}
