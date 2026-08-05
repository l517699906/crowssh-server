package com.llf.ai.domain.auth.model.valobj;

/**
 * 新设备身份及只返回一次的访问令牌。
 */
public record DeviceRegistration(String principalId, String accessToken) {
}
