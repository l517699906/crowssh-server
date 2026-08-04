package com.llf.ai.domain.ssh.service.terminal;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.llf.ai.domain.ssh.adapter.port.CommandExecutionResult;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
public class SshTerminalService implements ISshTerminalService {

    /** AI 命令最长执行 8 分钟，部署脚本中的系统级 timeout 必须小于该值。 */
    private static final long AI_COMMAND_TIMEOUT_MS = 480_000L;

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;
    private final ISshConnectionOwnershipService sshConnectionOwnershipService;

    /** 会话ID -> 终端会话实体 映射 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService,
                              ISshConnectionOwnershipService sshConnectionOwnershipService) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionService;
        this.sshConnectionOwnershipService = sshConnectionOwnershipService;
    }

    @Override
    public TerminalSessionEntity openTerminal(String ownerId, String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 校验连接归属并检查SSH连接是否已建立
        sshConnectionOwnershipService.requireOwnership(ownerId, connectionId);
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        // 2. 通过基础设施层打开独立终端会话；同一 SSH 连接可承载多个 Shell。
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 3. 创建并缓存会话实体
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .ownerId(ownerId)
                .cols(cols)
                .rows(rows)
                .status(1)
                .createdAt(LocalDateTime.now())
                .lastActiveAt(LocalDateTime.now())
                .build();

        sessionCache.put(sessionId, entity);
        log.info("终端会话创建成功 sessionId={}", sessionId);

        return entity;
    }

    @Override
    public String executeCommand(String ownerId, String sessionId, String command) {
        return executeCommandWithResult(ownerId, sessionId, command).output();
    }

    @Override
    public CommandExecutionResult executeCommandWithResult(
            String ownerId, String sessionId, String command) {
        log.debug("执行命令 sessionId={} command={}", sessionId, command);

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
        entity.touch();

        log.debug("命令执行完成 sessionId={} outputLength={} exitCode={} timedOut={} exitCodeKnown={}",
                sessionId, result.output().length(), result.exitCode(), result.timedOut(), result.exitCodeKnown());

        return result;
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
        entity.touch();
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
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        entity.setStatus(2);
        terminalSessionService.closeSession(sessionId);
        log.info("终端会话已关闭 sessionId={}", sessionId);
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
        return terminalSessionService.read(sessionId);
    }

    @Override
    public void writeTerminal(String ownerId, String sessionId, String input) {
        TerminalSessionEntity entity = getActiveSession(ownerId, sessionId);
        if (entity == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        terminalSessionService.write(sessionId, input);
        entity.touch();
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
        if (!sessionCache.remove(sessionId, entity)) {
            return;
        }
        entity.setStatus(2);
        terminalSessionService.closeSession(sessionId);
        log.warn("终端底层通道已失效，清理领域会话 sessionId={} connectionId={}",
                sessionId, entity.getConnectionId());
    }

}
