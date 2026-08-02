package com.llf.ai.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuntimeModelListRequestDTO {

    private String provider;

    private String baseUrl;

    private String apiKey;

    @Override
    public String toString() {
        return "RuntimeModelListRequestDTO{" +
                "provider='" + provider + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='***'" +
                '}';
    }
}
