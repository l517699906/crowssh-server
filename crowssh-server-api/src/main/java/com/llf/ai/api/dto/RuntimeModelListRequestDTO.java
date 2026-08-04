package com.llf.ai.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuntimeModelListRequestDTO {

    private String provider;

    private String protocol;

    private String baseUrl;

    private String apiKey;

    private String authType;

    private String authHeader;

    private String authPrefix;

    private String modelListPath;

    @Override
    public String toString() {
        return "RuntimeModelListRequestDTO{" +
                "provider='" + provider + '\'' +
                ", protocol='" + protocol + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" +
                ", authType='" + authType + '\'' +
                ", authHeader='" + authHeader + '\'' +
                ", modelListPath='" + modelListPath + '\'' +
                '}';
    }
}
