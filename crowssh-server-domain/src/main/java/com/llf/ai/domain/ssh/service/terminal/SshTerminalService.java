package com.llf.ai.domain.ssh.service.terminal;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.llf.ai.domain.ssh.adapter.port.CommandExecutionResult;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.config.SessionGovernanceProperties;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import com.llf.ai.domain.ssh.service.ITerminalSessionReaper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH终端领域服务实现
 * 遵循单一职责原则，将终端会话管理委托给基础设施层
 *
 * @author llf
 */
@Slf4j
@Service
public class SshTerminalService implements ISshTerminalService, ITerminalSessionReaper {

    /** AI 命令最长执行 8 分钟，部署脚本中的系统级 timeout 必须小于该值。 */
    private static final long AI_COMMAND_TIMEOUT_MS = 480_000L;
    private static final Logger DIAGNOSTIC =
            LoggerFactory.getLogger("CROWSSH_TERMINAL_DIAGNOSTIC");

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;
    private final ISshConnectionOwnershipService sshConnectionOwnershipService;
    private final Clock clock;
    private final SessionGovernanceProperties governanceProperties;

    /** 会话ID -> 终端会话实体 映射 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    /**
     * Spring 装配入口：使用系统默认时钟 + 注入的治理配置。
     */
    @Autowired
    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService,
                              ISshConnectionOwnershipService sshConnectionOwnershipService,
                              SessionGovernanceProperties governanceProperties) {
        this(sshSessionService, terminalSessionService, sshConnectionOwnershipService,
                Clock.systemDefaultZone(), governanceProperties);
    }

    /**
     * 规范构造器：可注入 Clock 与治理配置，供单元测试用可控时钟驱动 TTL。
     */
    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService,
                              ISshConnectionOwnershipService sshConnectionOwnershipService,
                              Clock clock,
                              SessionGovernanceProperties governanceProperties) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionService;
        this.sshConnectionOwnershipService = sshConnectionOwnershipService;
        this.clock = clock;
        this.governanceProperties = governanceProperties;
    }

    /**
     * 兼容构造器：保持既有三参签名，使用系统时钟 + 默认治理配置。
     */
    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService,
                              ISshConnectionOwnershipService sshConnectionOwnershipService) {
        this(sshSessionService, terminalSessionService, sshConnectionOwnershipService,
                Clock.systemDefaultZone(), new SessionGovernanceProperties());
    }

    @Override
    public TerminalSessionEntity openTerminal(String ownerId, String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 校验连接归属并检查SSH连接是否已建立
        sshConnectionOwnershipService.requireOwnership(ownerId, connectionId);
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        // 2. 单用户会话配额校验，防止无限开会话耗尽资源。
        if (countActiveSessionsForOwner(ownerId) >= governanceProperties.getMaxSessionsPerOwner()) {
            log.warn("单用户终端会话数已达上限 ownerId={} limit={}",
                    ownerId, governanceProperties.getMaxSessionsPerOwner());
            throw new IllegalStateException("已达到单用户最大终端会话数");
        }

        // 3. 通过基础设施层打开独立终端会话；同一 SSH 连接可承载多个 Shell。
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 4. 创建并缓存会话实体
        LocalDateTime now = LocalDateTime.now(clock);
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .ownerId(ownerId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(now)
                .lastActiveAt(now)
                .build();

        sessionCache.put(sessionId, entity);
        log.info("终端会话创建成功 sessionId={}", sessionId);
        DIAGNOSTIC.info("event=terminal_opened sessionId={} connectionId={} cols={} rows={}",
                sessionId, connectionId, cols, rows);

        return entity;
    }

    @Override
    public String executeCommand(String ownerId, String sessionId, String command) {
        return executeCommandWithResult(ownerId, sessionId, command).output();
    }

    @Override
    public CommandExecutionResult executeCommandWithResult(
            String ownerId, String sessionId, String command) {
        log.debug("执行命令 sessionId={} commandLength={}",
                sessionId, command == null ? 0 : command.length());

        // 1. 校验会话
        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        // 2. 使用独立 SSH exec channel 等待命令执行完成。
        CommandExecutionResult result;
        try {
            result = terminalSessionService.executeCommandAndWaitResult(
                    sessionId, command, AI_COMMAND_TIMEOUT_MS);
        } catch (RuntimeException e) {
            if (!terminalSessionService.sessionExists(sessionId)) {
                invalidateSession(sessionId, entity);
            }
            throw e;
        }

        // 3. 更新活跃时间
        entity.touch(LocalDateTime.now(clock));

        log.debug("命令执行完成 sessionId={} outputLength={} exitCode={} timedOut={} exitCodeKnown={}",
                sessionId, result.output().length(), result.exitCode(), result.timedOut(), result.exitCodeKnown());

        return result;
    }

    @Override
    public boolean cancelCommand(String ownerId, String sessionId) {
        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        boolean cancelled = terminalSessionService.cancelActiveCommand(sessionId);
        if (cancelled) entity.touch(LocalDateTime.now(clock));
        return cancelled;
    }

    @Override
    public void resizeTerminal(String ownerId, String sessionId, int cols, int rows) {
        log.debug("调整终端大小 sessionId={} cols={} rows={}", sessionId, cols, rows);

        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        terminalSessionService.resize(sessionId, cols, rows);

        entity.setCols(cols);
        entity.setRows(rows);
        entity.touch(LocalDateTime.now(clock));
    }

    @Override
    public TerminalSessionEntity getTerminalSession(String ownerId, String sessionId) {
        return getActiveSession(ownerId, sessionId);
    }

    @Override
    public void closeTerminal(String ownerId, String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);

        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null || !sessionCache.remove(sessionId, entity)) {
            log.info("终端会话已关闭或不存在，忽略重复关闭 sessionId={}", sessionId);
            return;
        }
        entity.setStatus(2);
        terminalSessionService.closeSession(sessionId);
        log.info("终端会话已关闭 sessionId={}", sessionId);
        DIAGNOSTIC.info("event=terminal_closed sessionId={} reason=explicit_close", sessionId);
    }

    @Override
    public boolean sessionExists(String ownerId, String sessionId) {
        return getActiveSession(ownerId, sessionId) != null;
    }

    @Override
    public String readTerminal(String ownerId, String sessionId) {
        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        String output = terminalSessionService.read(sessionId);
        // 客户端仍在读取或 WebSocket 心跳正常，说明终端会话仍被使用。
        entity.touch(LocalDateTime.now(clock));
        return output;
    }

    @Override
    public void writeTerminal(String ownerId, String sessionId, String input) {
        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        terminalSessionService.write(sessionId, input);
        entity.touch(LocalDateTime.now(clock));
    }

    private TerminalSessionEntity getActiveSession(String ownerId, String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive() || !Objects.equals(entity.getOwnerId(), ownerId)) {
            return null;
        }
        if (terminalSessionService.sessionExists(sessionId)) {
            return entity;
        }

        invalidateSession(sessionId, entity);
        return null;
    }

    private void invalidateSession(String sessionId, TerminalSessionEntity entity) {
        if (!evict(sessionId, entity)) {
            return;
        }
        log.warn("终端底层通道已失效，清理领域会话 sessionId={} connectionId={}",
                sessionId, entity.getConnectionId());
        DIAGNOSTIC.warn("event=terminal_invalidated sessionId={} connectionId={} reason=channel_closed",
                sessionId, entity.getConnectionId());
    }

    /**
     * compare-and-remove 驱逐会话并关闭底层通道，供失效清理与 TTL 回收共用。
     *
     * @return true 表示本次调用成功移除（未被并发抢先）
     */
    private boolean evict(String sessionId, TerminalSessionEntity entity) {
        if (!sessionCache.remove(sessionId, entity)) {
            return false;
        }
        entity.setStatus(2);
        terminalSessionService.closeSession(sessionId);
        return true;
    }

    /**
     * 统计指定设备身份当前持有的活跃(status==1)会话数。
     */
    private long countActiveSessionsForOwner(String ownerId) {
        return sessionCache.values().stream()
                .filter(entity -> entity.isActive() && Objects.equals(entity.getOwnerId(), ownerId))
                .count();
    }

    /**
     * 回收空闲超时的终端会话：最后活跃时间早于 (now - idleTimeout) 的会话被驱逐。
     *
     * <p>用注入的 Clock 判定时间，配合可控时钟即可单元测试而无需真实等待。
     * 复用 {@link #evict} 的 compare-and-remove，避免与并发关闭/重开竞争。
     *
     * @return 本次回收的会话数
     */
    @Override
    public int reapIdleSessions() {
        LocalDateTime cutoff = LocalDateTime.now(clock)
                .minusNanos(governanceProperties.getIdleTimeoutMillis() * 1_000_000L);
        int reaped = 0;
        for (Map.Entry<String, TerminalSessionEntity> entry : sessionCache.entrySet()) {
            TerminalSessionEntity entity = entry.getValue();
            LocalDateTime lastActive = entity.getLastActiveAt();
            if (lastActive != null && lastActive.isBefore(cutoff)
                    && evict(entry.getKey(), entity)) {
                reaped++;
                log.info("回收空闲终端会话 sessionId={} lastActiveAt={}",
                        entry.getKey(), lastActive);
                DIAGNOSTIC.warn("event=terminal_closed sessionId={} reason=idle_timeout lastActiveAt={}",
                        entry.getKey(), lastActive);
            }
        }
        return reaped;
    }

}
