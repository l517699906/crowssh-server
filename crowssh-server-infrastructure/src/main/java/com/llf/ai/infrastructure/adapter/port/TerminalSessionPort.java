package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端会话管理器
 * 基础设施层实现，管理 Shell 通道的创建、读写、关闭
 *
 * @author llf
 */
@Slf4j
@Component
public class TerminalSessionPort implements ITerminalSessionPort {

    private static final int WRITE_MAX_RETRIES = 2;
    private static final String TERMINAL_TYPE = "xterm-256color";

    private final SshSessionPort sshSessionService;

    public TerminalSessionPort(SshSessionPort sshSessionService) {
        this.sshSessionService = sshSessionService;
    }

    /** sessionId -> Shell 通道 */
    private final Map<String, Session.Shell> channels = new ConcurrentHashMap<>();

    /** sessionId -> 输出流 */
    private final Map<String, OutputStream> outputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 输入流 */
    private final Map<String, InputStream> inputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 未读输出缓冲区 */
    private final Map<String, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    /** sessionId -> 读取线程是否存活 */
    private final Map<String, Boolean> readerAlive = new ConcurrentHashMap<>();

    /** connectionId -> 当前活跃的 sessionId（一个连接只允许一个终端会话） */
    private final Map<String, String> activeConnectionSession = new ConcurrentHashMap<>();

    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        // 同一 connectionId 只允许一个终端会话，先关闭旧的
        String oldSessionId = activeConnectionSession.get(connectionId);
        if (oldSessionId != null) {
            log.info("关闭旧终端会话以避免重复 connectionId={} oldSessionId={}", connectionId, oldSessionId);
            cleanup(oldSessionId);
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            Session.Shell channel = openShell(connectionId, cols, rows);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channels.put(sessionId, channel);
            inputStreams.put(sessionId, in);
            outputStreams.put(sessionId, out);
            outputBuffers.put(sessionId, new StringBuilder());
            activeConnectionSession.put(connectionId, sessionId);

            // 启动输出读取线程，持续读取 shell 输出到缓冲区
            startOutputReader(sessionId, in);

            // 等待 Shell 首次输出到达 + 额外等待让 MOTD 完整积累
            // 然后消费缓冲区，作为 initialOutput 返回给前端
            // 这样前端不再依赖轮询获取初始输出，彻底解决"有时显示有时不显示"的问题
            StringBuilder buffer = outputBuffers.get(sessionId);
            long waitDeadline = System.currentTimeMillis() + 3000; // 最多等 3s 等首数据
            try {
                // 阶段1：等首数据到达
                while (System.currentTimeMillis() < waitDeadline) {
                    synchronized (buffer) {
                        if (!buffer.isEmpty()) {
                            break;
                        }
                    }
                    Thread.sleep(30);
                }
                // 阶段2：额外等 200ms 让 MOTD/prompt 完整到达
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            log.info("终端会话打开成功 sessionId={} connectionId={}", sessionId, connectionId);
            return sessionId;

        } catch (Exception e) {
            log.error("打开终端会话失败 connectionId={}", connectionId, e);
            cleanup(sessionId);
            throw new RuntimeException("打开终端失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String sessionId, String command) {
        OutputStream out = outputStreams.get(sessionId);
        if (out == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= WRITE_MAX_RETRIES; attempt++) {
            try {
                out.write(command.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return; // 成功
            } catch (IOException e) {
                lastError = e;
                log.warn("写入终端失败 (attempt={}/{}) sessionId={} reason={}",
                        attempt, WRITE_MAX_RETRIES, sessionId, e.getMessage());
                // 最后一次重试后抛出异常
                if (attempt == WRITE_MAX_RETRIES) {
                    break;
                }
                // 短暂等待后重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.error("写入终端失败 (已重试 {} 次) sessionId={}", WRITE_MAX_RETRIES, sessionId, lastError);
        throw new RuntimeException("写入终端失败: " + lastError.getMessage(), lastError);
    }

    @Override
    public String read(String sessionId) {
        StringBuilder buffer = outputBuffers.get(sessionId);
        if (buffer == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        // 非阻塞：直接返回缓冲区当前内容，不等
        // 前端轮询本身就是等待机制，不需要后端再等
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return "";
            }
            String output = buffer.toString();
            buffer.setLength(0);
            return output;
        }
    }

    @Override
    public void resize(String sessionId, int cols, int rows) {
        Session.Shell channel = channels.get(sessionId);
        if (channel == null || !channel.isOpen() || channel.isEOF()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        try {
            channel.changeWindowDimensions(cols, rows, 0, 0);
            log.debug("终端大小已调整 sessionId={} {}x{}", sessionId, cols, rows);
        } catch (Exception e) {
            log.error("调整终端大小失败 sessionId={}", sessionId, e);
            throw new RuntimeException("调整终端大小失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeSession(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);
        cleanup(sessionId);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        Session.Shell channel = channels.get(sessionId);
        return channel != null && channel.isOpen() && !channel.isEOF();
    }

    // ========== 内部方法 ==========

    private Session.Shell openShell(String connectionId, int cols, int rows) throws IOException {
        Session session = sshSessionService.openSession(connectionId);
        try {
            session.allocatePTY(TERMINAL_TYPE, cols, rows, 0, 0,
                    Collections.<PTYMode, Integer>emptyMap());
            return session.startShell();
        } catch (IOException | RuntimeException e) {
            try {
                session.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    /**
     * 启动输出读取线程
     * SocketTimeoutException 时继续循环（不是真正的断连），
     * 只有 EOF（-1）或真正的 IOException 才退出。
     */
    private void startOutputReader(String sessionId, InputStream in) {
        readerAlive.put(sessionId, true);
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (Boolean.TRUE.equals(readerAlive.get(sessionId))) {
                    int len;
                    try {
                        len = in.read(buf);
                    } catch (java.net.SocketTimeoutException e) {
                        log.debug("终端读取超时，继续等待 sessionId={}", sessionId);
                        continue;
                    }
                    if (len == -1) {
                        log.warn("终端 Shell Channel EOF sessionId={}", sessionId);
                        break;
                    }
                    String text = new String(buf, 0, len, StandardCharsets.UTF_8);
                    StringBuilder buffer = outputBuffers.get(sessionId);
                    if (buffer != null) {
                        synchronized (buffer) {
                            buffer.append(text);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("终端输出读取异常 sessionId={} reason={}", sessionId, e.getMessage());
            } finally {
                readerAlive.computeIfPresent(sessionId, (key, value) -> false);

                Session.Shell channel = channels.get(sessionId);
                boolean channelOpen = channel != null && channel.isOpen();
                boolean channelEof = channel != null && channel.isEOF();
                log.warn("终端输出读取线程退出 sessionId={} channelOpen={} channelEof={}",
                        sessionId, channelOpen, channelEof);

                StringBuilder buffer = outputBuffers.get(sessionId);
                if (buffer != null) {
                    synchronized (buffer) {
                        buffer.append("\u001b[31m\r\n[连接已断开]\u001b[0m\r\n");
                    }
                }
            }
        }, "terminal-reader-" + sessionId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 清理资源
     */
    private void cleanup(String sessionId) {
        // 清理 connectionId -> sessionId 映射
        activeConnectionSession.entrySet().removeIf(e -> sessionId.equals(e.getValue()));

        readerAlive.remove(sessionId);

        Session.Shell channel = channels.remove(sessionId);
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                log.debug("关闭终端 Shell 通道失败 sessionId={} reason={}", sessionId, e.getMessage());
            }
        }

        try {
            OutputStream out = outputStreams.remove(sessionId);
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            log.debug("关闭终端输出流失败 sessionId={} reason={}", sessionId, e.getMessage());
        }

        try {
            InputStream in = inputStreams.remove(sessionId);
            if (in != null) {
                in.close();
            }
        } catch (IOException e) {
            log.debug("关闭终端输入流失败 sessionId={} reason={}", sessionId, e.getMessage());
        }

        outputBuffers.remove(sessionId);
    }

}
