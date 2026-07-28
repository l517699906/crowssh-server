package com.llf.ai.domain.ssh.service.terminal;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
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

    private final ISshSessionPort sshSessionService;
    private final ITerminalSessionPort terminalSessionService;

    /** 会话ID -> 终端会话实体 映射 */
    private final Map<String, TerminalSessionEntity> sessionCache = new ConcurrentHashMap<>();

    public SshTerminalService(ISshSessionPort sshSessionService,
                              ITerminalSessionPort terminalSessionService) {
        this.sshSessionService = sshSessionService;
        this.terminalSessionService = terminalSessionService;
    }

    @Override
    public TerminalSessionEntity openTerminal(String connectionId, int cols, int rows) {
        log.info("打开终端会话 connectionId={} cols={} rows={}", connectionId, cols, rows);

        // 1. 检查SSH连接是否已建立
        if (!sshSessionService.isConnected(connectionId)) {
            throw new IllegalStateException("SSH连接未建立，请先连接");
        }

        // 2. 通过基础设施层打开独立终端会话；同一 SSH 连接可承载多个 Shell。
        String sessionId = terminalSessionService.openTerminal(connectionId, cols, rows);

        // 3. 创建并缓存会话实体
        TerminalSessionEntity entity = TerminalSessionEntity.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
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
    public String executeCommand(String sessionId, String command) {
        log.debug("执行命令 sessionId={} command={}", sessionId, command);

        // 1. 校验会话
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        // 2. 使用 executeCommandAndWait 等待命令执行完成
        //    超时时间 30 秒（大部分命令应该能在 30 秒内完成）
        String output = terminalSessionService.executeCommandAndWait(sessionId, command, 30000);

        // 3. 更新活跃时间
        entity.touch();

        log.debug("命令执行完成 sessionId={} outputLength={}", sessionId, output.length());

        return output;
    }

    @Override
    public void resizeTerminal(String sessionId, int cols, int rows) {
        log.debug("调整终端大小 sessionId={} cols={} rows={}", sessionId, cols, rows);

        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }

        terminalSessionService.resize(sessionId, cols, rows);

        entity.setCols(cols);
        entity.setRows(rows);
        entity.touch();
    }

    @Override
    public TerminalSessionEntity getTerminalSession(String sessionId) {
        return sessionCache.get(sessionId);
    }

    @Override
    public void closeTerminal(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);

        TerminalSessionEntity entity = sessionCache.remove(sessionId);
        if (entity != null) {
            terminalSessionService.closeSession(sessionId);
            log.info("终端会话已关闭 sessionId={}", sessionId);
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionCache.containsKey(sessionId);
    }

    @Override
    public String readTerminal(String sessionId) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        return terminalSessionService.read(sessionId);
    }

    @Override
    public void writeTerminal(String sessionId, String input) {
        TerminalSessionEntity entity = sessionCache.get(sessionId);
        if (entity == null || !entity.isActive()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭");
        }
        terminalSessionService.write(sessionId, input);
        entity.touch();
    }

}
