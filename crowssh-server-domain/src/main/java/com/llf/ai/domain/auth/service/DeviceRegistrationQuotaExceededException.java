package com.llf.ai.domain.auth.service;

/**
 * 活跃设备身份已达到配置上限。
 */
public class DeviceRegistrationQuotaExceededException extends RuntimeException {

    public DeviceRegistrationQuotaExceededException(int maximum) {
        super("设备身份配额已用尽，当前上限为 " + maximum);
    }
}
