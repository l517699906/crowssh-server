package com.llf.ai.domain.ssh.adapter.port;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 终端会话实体
 * 聚合根，管理一个 SSH 终端会话的生命周期
 *
 * @author llf
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TerminalSessionEntity {

    /** 会话ID */
    private String sessionId;

    /** SSH连接ID */
    private String connectionId;

    /** 会话所属设备身份ID */
    private String ownerId;

    /** 终端列数 */
    private int cols;

    /** 终端行数 */
    private int rows;

    /** SSH 通道ID */
    private String channelId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveAt;

    /** 会话状态: 0-未激活, 1-活跃, 2-已关闭 */
    private int status;

    /**
     * 校验会话是否有效
     */
    public boolean isActive() {
        return status == 1 && sessionId != null && !sessionId.isBlank();
    }

    /**
     * 更新最后活跃时间
     */
    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

}
