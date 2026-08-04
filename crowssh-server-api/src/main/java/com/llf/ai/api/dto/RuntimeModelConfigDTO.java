package com.llf.ai.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuntimeModelConfigDTO {

    private String provider;

    private String protocol;

    private String baseUrl;

    private String apiKey;

    private String authType;

    private String authHeader;

    private String authPrefix;

    private String modelListPath;

    private String model;

    private Double temperature;

    private Boolean omitTemperature;

    private String tokenParameter;

    private Integer maxTokens;

    @Override
    public String toString() {
        return "RuntimeModelConfigDTO{" +
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
