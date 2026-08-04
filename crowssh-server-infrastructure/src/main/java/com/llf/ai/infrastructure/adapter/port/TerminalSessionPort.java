package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.CommandExecutionResult;
import com.llf.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final long COMMAND_CANCEL_GRACE_MS = 2000;
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 2000;
    private static final String WORKING_DIRECTORY_SHELL_INTEGRATION =
            " __crowssh_emit_cwd() { printf '\\033]7;file://crowssh%s\\007' \"$PWD\"; }; "
                    + "if [ -n \"${BASH_VERSION:-}\" ]; then "
                    + "case \";${PROMPT_COMMAND:-};\" in *\";__crowssh_emit_cwd;\"*) ;; "
                    + "*) PROMPT_COMMAND=\"__crowssh_emit_cwd${PROMPT_COMMAND:+;$PROMPT_COMMAND}\" ;; esac; "
                    + "elif [ -n \"${ZSH_VERSION:-}\" ]; then "
                    + "autoload -Uz add-zsh-hook >/dev/null 2>&1 "
                    + "&& add-zsh-hook precmd __crowssh_emit_cwd; "
                    + "else PS1='$(__crowssh_emit_cwd)'\"${PS1:-}\"; fi; "
                    + "__crowssh_emit_cwd";

    private final SshSessionPort sshSessionService;

    public TerminalSessionPort(SshSessionPort sshSessionService) {
        this.sshSessionService = sshSessionService;
    }

    /** sessionId -> Shell 通道 */
    private final Map<String, Session.Shell> channels = new ConcurrentHashMap<>();

    /** sessionId -> SSH 连接ID，用于为 AI 命令创建独立 exec channel */
    private final Map<String, String> connectionIds = new ConcurrentHashMap<>();

    /** sessionId -> 输出流 */
    private final Map<String, OutputStream> outputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 输入流 */
    private final Map<String, InputStream> inputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 未读输出缓冲区（前端轮询消费） */
    private final Map<String, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    /** sessionId -> 读取线程是否存活 */
    private final Map<String, Boolean> readerAlive = new ConcurrentHashMap<>();

    /** sessionId -> 人工终端输入锁 */
    private final Map<String, ReentrantLock> terminalWriteLocks = new ConcurrentHashMap<>();

    /** sessionId -> AI 命令锁，同一终端绑定的 AI 命令保持串行 */
    private final Map<String, ReentrantLock> commandExecutionLocks = new ConcurrentHashMap<>();

    /** sessionId -> 当前运行中的 AI exec channel */
    private final Map<String, Session.Command> activeCommands = new ConcurrentHashMap<>();

    /** sessionId -> 持久交互 Shell 最近上报的工作目录 */
    private final Map<String, String> workingDirectories = new ConcurrentHashMap<>();

    /** sessionId -> OSC 7 流式解析器 */
    private final Map<String, TerminalWorkingDirectoryTracker> workingDirectoryTrackers = new ConcurrentHashMap<>();

    /** sessionId -> Shell 集成命令回显过滤器 */
    private final Map<String, OneShotTerminalOutputFilter> shellIntegrationOutputFilters = new ConcurrentHashMap<>();

    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            Session.Shell channel = openShell(connectionId, cols, rows);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channels.put(sessionId, channel);
            connectionIds.put(sessionId, connectionId);
            inputStreams.put(sessionId, in);
            outputStreams.put(sessionId, out);
            outputBuffers.put(sessionId, new StringBuilder());
            workingDirectoryTrackers.put(sessionId, new TerminalWorkingDirectoryTracker(
                    workingDirectory -> updateWorkingDirectory(sessionId, workingDirectory)));
            shellIntegrationOutputFilters.put(sessionId,
                    new OneShotTerminalOutputFilter(WORKING_DIRECTORY_SHELL_INTEGRATION));
            // 启动输出读取线程，持续读取 shell 输出到缓冲区
            startOutputReader(sessionId, in);
            installWorkingDirectoryTracking(sessionId, out);

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
        ReentrantLock commandLock = terminalWriteLocks.computeIfAbsent(sessionId, key -> new ReentrantLock(true));
        commandLock.lock();
        try {
            ensureSessionOpen(sessionId);

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
                    if (!sessionExists(sessionId)) {
                        break;
                    }
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
            cleanup(sessionId);
            log.error("写入终端失败 (已重试 {} 次) sessionId={}", WRITE_MAX_RETRIES, sessionId, lastError);
            throw new RuntimeException("终端会话已断开，无法写入: "
                    + (lastError == null ? "未知错误" : lastError.getMessage()), lastError);
        } finally {
            commandLock.unlock();
        }
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

    /**
     * 在独立 SSH exec channel 中执行 AI 命令。
     * <p>
     * 命令不会写入持久交互 Shell，因此超时、set -e、exit 或前台进程都不会污染终端会话。
     * stdout 和 stderr 会同时镜像到前端终端缓冲区。
     */
    @Override
    public String executeCommandAndWait(String sessionId, String command, long timeoutMs) {
        return executeCommandAndWaitResult(sessionId, command, timeoutMs).output();
    }

    @Override
    public CommandExecutionResult executeCommandAndWaitResult(String sessionId, String command, long timeoutMs) {
        ensureSessionOpen(sessionId);

        ReentrantLock commandLock = commandExecutionLocks.computeIfAbsent(
                sessionId, key -> new ReentrantLock(true));
        commandLock.lock();
        try {
            return executeCommandInExecChannel(sessionId, command, timeoutMs);
        } finally {
            commandLock.unlock();
        }
    }

    private CommandExecutionResult executeCommandInExecChannel(
            String sessionId,
            String command,
            long timeoutMs) {
        ensureSessionOpen(sessionId);

        String connectionId = connectionIds.get(sessionId);
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalStateException("终端未绑定 SSH 连接 sessionId=" + sessionId);
        }

        StringBuilder frontBuffer = outputBuffers.get(sessionId);
        if (frontBuffer == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        String safeCommand = command == null ? "" : command;
        String execCommandText = commandInWorkingDirectory(safeCommand, workingDirectories.get(sessionId));
        StringBuilder resultBuffer = new StringBuilder();
        Session execSession = null;
        Session.Command execCommand = null;
        Thread stdoutReader = null;
        Thread stderrReader = null;
        boolean timedOut = false;
        try {
            appendToTerminal(frontBuffer, "\r\n$ " + safeCommand + "\r\n");

            execSession = sshSessionService.openSession(connectionId);
            execCommand = execSession.exec(execCommandText);
            activeCommands.put(sessionId, execCommand);
            ensureSessionOpen(sessionId);
            stdoutReader = startCommandOutputReader(
                    sessionId, "stdout", execCommand.getInputStream(), resultBuffer, frontBuffer);
            stderrReader = startCommandOutputReader(
                    sessionId, "stderr", execCommand.getErrorStream(), resultBuffer, frontBuffer);

            execCommand.join(timeoutMs, TimeUnit.MILLISECONDS);
            timedOut = execCommand.isOpen();

            if (timedOut) {
                log.warn("AI 命令执行超时，终止独立 exec channel sessionId={} command={}",
                        sessionId, safeCommand);
                signalCommand(execCommand, Signal.TERM, sessionId);
                execCommand.join(COMMAND_CANCEL_GRACE_MS, TimeUnit.MILLISECONDS);
            }

            if (!timedOut) {
                joinOutputReaders(stdoutReader, stderrReader, OUTPUT_DRAIN_TIMEOUT_MS);
            }

            closeQuietly(execCommand, "AI 命令 channel", sessionId);
            joinOutputReaders(stdoutReader, stderrReader, OUTPUT_DRAIN_TIMEOUT_MS);

            String output;
            synchronized (resultBuffer) {
                output = normalizeCommandOutput(resultBuffer.toString());
            }

            if (timedOut) {
                return new CommandExecutionResult(output, -1, true, false);
            }

            Integer exitStatus = execCommand.getExitStatus();
            boolean exitCodeKnown = exitStatus != null;
            int exitCode = exitCodeKnown ? exitStatus : -1;

            log.info("AI 命令执行完成 sessionId={} outputLength={} exitCode={} exitCodeKnown={}",
                    sessionId, output.length(), exitCode, exitCodeKnown);
            return new CommandExecutionResult(output, exitCode, false, exitCodeKnown);
        } catch (IOException e) {
            throw new RuntimeException("创建 SSH exec channel 失败: " + e.getMessage(), e);
        } finally {
            if (execCommand != null) {
                activeCommands.remove(sessionId, execCommand);
            }
            if (execCommand == null) {
                closeQuietly(execSession, "AI exec session", sessionId);
            } else if (execCommand.isOpen()) {
                closeQuietly(execCommand, "AI 命令 channel", sessionId);
            }
        }
    }

    private Thread startCommandOutputReader(
            String sessionId,
            String streamName,
            InputStream input,
            StringBuilder resultBuffer,
            StringBuilder frontBuffer) {
        Thread reader = new Thread(() -> {
            byte[] bytes = new byte[4096];
            try {
                while (true) {
                    int length;
                    try {
                        length = input.read(bytes);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    if (length < 0) {
                        return;
                    }
                    String text = new String(bytes, 0, length, StandardCharsets.UTF_8);
                    synchronized (resultBuffer) {
                        resultBuffer.append(text);
                    }
                    appendToTerminal(frontBuffer, normalizeTerminalOutput(text));
                }
            } catch (IOException e) {
                log.debug("读取 AI 命令{}失败 sessionId={} reason={}",
                        streamName, sessionId, e.getMessage());
            }
        }, "ai-command-" + streamName + "-" + sessionId);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void joinOutputReaders(Thread stdoutReader, Thread stderrReader, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        joinOutputReader(stdoutReader, deadline);
        joinOutputReader(stderrReader, deadline);
    }

    private void joinOutputReader(Thread reader, long deadline) {
        if (reader == null) {
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        try {
            reader.join(remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 AI 命令输出被中断", e);
        }
    }

    private void signalCommand(Session.Command command, Signal signal, String sessionId) {
        try {
            command.signal(signal);
        } catch (IOException | RuntimeException e) {
            log.debug("发送 AI 命令终止信号失败 sessionId={} reason={}", sessionId, e.getMessage());
        }
    }

    private void appendToTerminal(StringBuilder buffer, String text) {
        synchronized (buffer) {
            buffer.append(text);
        }
    }

    private String normalizeTerminalOutput(String output) {
        return output.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    private String normalizeCommandOutput(String output) {
        return output.replace("\r", "").stripTrailing();
    }

    private void closeQuietly(Closeable closeable, String resourceName, String sessionId) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            log.debug("关闭{}失败 sessionId={} reason={}", resourceName, sessionId, e.getMessage());
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
        return channel != null
                && channel.isOpen()
                && !channel.isEOF()
                && outputStreams.containsKey(sessionId)
                && Boolean.TRUE.equals(readerAlive.get(sessionId));
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

    private void installWorkingDirectoryTracking(String sessionId, OutputStream output) throws IOException {
        output.write((WORKING_DIRECTORY_SHELL_INTEGRATION + "\r").getBytes(StandardCharsets.UTF_8));
        output.flush();
        log.debug("已安装终端工作目录跟踪 sessionId={}", sessionId);
    }

    private void updateWorkingDirectory(String sessionId, String workingDirectory) {
        if (!Boolean.TRUE.equals(readerAlive.get(sessionId))) {
            return;
        }
        workingDirectories.put(sessionId, workingDirectory);
        log.debug("终端工作目录已更新 sessionId={} cwd={}", sessionId, workingDirectory);
    }

    private String commandInWorkingDirectory(String command, String workingDirectory) {
        if (command == null || command.isBlank() || workingDirectory == null || workingDirectory.isBlank()) {
            return command;
        }
        return "cd -- " + quoteShellArgument(workingDirectory) + " && " + command;
    }

    private String quoteShellArgument(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
                    TerminalWorkingDirectoryTracker tracker = workingDirectoryTrackers.get(sessionId);
                    if (tracker != null) {
                        tracker.accept(buf, 0, len);
                    }
                    OneShotTerminalOutputFilter outputFilter = shellIntegrationOutputFilters.get(sessionId);
                    byte[] visibleBytes = outputFilter == null
                            ? java.util.Arrays.copyOf(buf, len)
                            : outputFilter.filter(buf, 0, len);
                    String text = new String(visibleBytes, StandardCharsets.UTF_8);
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

                cleanup(sessionId);
            }
        }, "terminal-reader-" + sessionId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 校验底层 Shell 通道是否仍然可用。
     */
    private void ensureSessionOpen(String sessionId) {
        if (sessionExists(sessionId)) {
            return;
        }
        cleanup(sessionId);
        throw new IllegalStateException("终端会话不存在或已断开 sessionId=" + sessionId);
    }

    /**
     * 清理资源
     */
    private void cleanup(String sessionId) {
        readerAlive.remove(sessionId);

        Session.Command activeCommand = activeCommands.remove(sessionId);
        if (activeCommand != null && activeCommand.isOpen()) {
            signalCommand(activeCommand, Signal.TERM, sessionId);
        }
        closeQuietly(activeCommand, "AI 命令 channel", sessionId);

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
        connectionIds.remove(sessionId);
        workingDirectories.remove(sessionId);
        workingDirectoryTrackers.remove(sessionId);
        shellIntegrationOutputFilters.remove(sessionId);
        terminalWriteLocks.remove(sessionId);
        commandExecutionLocks.remove(sessionId);
    }

}
