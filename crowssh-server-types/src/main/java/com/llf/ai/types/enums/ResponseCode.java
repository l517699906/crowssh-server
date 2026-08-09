package com.llf.ai.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),
    SFTP_VERSION_CONFLICT("SFTP_CONFLICT", "远程文件已被修改"),
    SSH_HOST_KEY_UNTRUSTED("SSH_HOST_KEY_UNTRUSTED", "SSH 主机密钥尚未信任"),
    SSH_HOST_KEY_CHANGED("SSH_HOST_KEY_CHANGED", "SSH 主机密钥已变化"),
    SSH_TARGET_BLOCKED("SSH_TARGET_BLOCKED", "SSH 目标地址被出站策略阻止"),
    DEVICE_REGISTRATION_DISABLED("DEVICE_REGISTRATION_DISABLED", "设备身份注册已关闭"),
    DEVICE_REGISTRATION_RATE_LIMITED("DEVICE_REGISTRATION_RATE_LIMITED", "设备身份注册请求过于频繁"),
    DEVICE_REGISTRATION_INVITE_INVALID("DEVICE_REGISTRATION_INVITE_INVALID", "设备身份注册码无效"),
    DEVICE_REGISTRATION_QUOTA_EXCEEDED("DEVICE_REGISTRATION_QUOTA_EXCEEDED", "设备身份注册配额已用尽"),
    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),
    ;

    private String code;
    private String info;

}
