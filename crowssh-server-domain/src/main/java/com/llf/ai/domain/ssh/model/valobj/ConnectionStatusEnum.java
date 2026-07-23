package com.llf.ai.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH连接状态枚举
 *
 * @author llf
 */
@Getter
@AllArgsConstructor
public enum ConnectionStatusEnum {

    DISCONNECTED(0, "未连接"),
    CONNECTED(1, "已连接"),
    CONNECTING(2, "连接中"),
    FAILED(3, "连接失败");

    private final int code;
    private final String desc;

    public static ConnectionStatusEnum fromCode(int code) {
        for (ConnectionStatusEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return DISCONNECTED; // 默认未连接
    }
}
