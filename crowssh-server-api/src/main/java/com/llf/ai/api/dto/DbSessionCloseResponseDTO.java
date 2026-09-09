package com.llf.ai.api.dto;

/** 关闭请求已接受与资源已经释放分开描述。 */
public record DbSessionCloseResponseDTO(String dbSessionId, String lifecycleStatus) { }
