package com.llf.ai.domain.ssh.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH认证类型枚举
 *
 * @author llf
 */
@Getter
@AllArgsConstructor
public enum AuthTypeEnum {

    PASSWORD(1, "密码认证"),
    PRIVATE_KEY(2, "私钥认证");

    private final int code;
    private final String desc;

    public static AuthTypeEnum fromCode(int code) {
        for (AuthTypeEnum value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return PASSWORD; // 默认密码认证
    }
}
