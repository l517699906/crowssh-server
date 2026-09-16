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
    SSH_MONITOR_UNSUPPORTED("SSH_MONITOR_UNSUPPORTED", "当前 SSH 主机不支持服务器监控"),
    SSH_MONITOR_UNAVAILABLE("SSH_MONITOR_UNAVAILABLE", "服务器监控暂时不可用"),
    DEVICE_REGISTRATION_DISABLED("DEVICE_REGISTRATION_DISABLED", "设备身份注册已关闭"),
    DEVICE_REGISTRATION_RATE_LIMITED("DEVICE_REGISTRATION_RATE_LIMITED", "设备身份注册请求过于频繁"),
    DEVICE_REGISTRATION_INVITE_INVALID("DEVICE_REGISTRATION_INVITE_INVALID", "设备身份注册码无效"),
    DEVICE_REGISTRATION_QUOTA_EXCEEDED("DEVICE_REGISTRATION_QUOTA_EXCEEDED", "设备身份注册配额已用尽"),
    DB_TARGET_BLOCKED("DB_TARGET_BLOCKED", "数据库目标地址被出站策略阻止"),
    DB_TLS_VERIFICATION_FAILED("DB_TLS_VERIFICATION_FAILED", "数据库 TLS 身份校验失败"),
    DB_CONNECTION_FAILED("DB_CONNECTION_FAILED", "数据库连接失败"),
    DB_SESSION_QUOTA_EXCEEDED("DB_SESSION_QUOTA_EXCEEDED", "数据库工作台会话配额已用尽"),
    DB_SESSION_NOT_FOUND("DB_SESSION_NOT_FOUND", "数据库工作台会话不存在"),
    DB_SESSION_BUSY("DB_SESSION_BUSY", "数据库会话当前正在执行其他操作"),
    DB_SESSION_BROKEN("DB_SESSION_BROKEN", "数据库会话已失效，请重新打开"),
    DB_CONTEXT_CHANGED("DB_CONTEXT_CHANGED", "数据库目标上下文已变化，请重新准备 SQL"),
    DB_STATEMENT_REJECTED("DB_STATEMENT_REJECTED", "SQL 未通过数据库执行策略"),
    DB_DATA_ACCESS_DENIED("DB_DATA_ACCESS_DENIED", "数据库数据不在 AI 允许范围内"),
    DB_EXECUTION_NOT_FOUND("DB_EXECUTION_NOT_FOUND", "数据库执行记录不存在"),
    DB_EXECUTION_EXPIRED("DB_EXECUTION_EXPIRED", "数据库执行记录已过期"),
    DB_CONFIG_CONFLICT("DB_CONFIG_CONFLICT", "数据库连接配置版本冲突"),
    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),
    ;

    private String code;
    private String info;

}
