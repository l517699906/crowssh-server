package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.Annotations;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 命令执行 ADK 工具，为智能体提供在 SSH 终端执行命令的能力
 * <p>
 * 使用 ADK 的 @Schema 注解定义参数，支持 FunctionTool.create()
 *
 * @author llf
 * 2026/7/8 07:30
 */
@Slf4j
@Service
public class SshExecuteAdkTool {

    @Resource
    private ISshTerminalService sshTerminalService;

    // 当前线程的终端会话 ID（使用 InheritableThreadLocal 支持异步线程继承）
    private static final InheritableThreadLocal<String> currentTerminalSession = new InheritableThreadLocal<>();

    // 工具执行观察器用于将可验证的执行状态转发给当前 SSE 请求，不传递完整终端输出
    private static final InheritableThreadLocal<ExecutionObserver> currentExecutionObserver = new InheritableThreadLocal<>();
    private static final Map<String, ExecutionObserver> executionObserversByTerminal = new ConcurrentHashMap<>();

    /** 当前会话级终端会话ID（由 Controller 设置，优先级低于 ThreadLocal） */
    private static volatile String sessionTerminalSessionId;

    /**
     * 设置当前线程的终端会话 ID
     */
    public static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
        sessionTerminalSessionId = terminalSessionId;
        log.info("[ThreadLocal] 设置终端会话: thread={}, terminalSession={}",
                Thread.currentThread().getName(), terminalSessionId);
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    public static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
        sessionTerminalSessionId = null;
    }

    public static void setExecutionObserver(String terminalSessionId, ExecutionObserver observer) {
        currentExecutionObserver.set(observer);
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            executionObserversByTerminal.put(terminalSessionId, observer);
        }
    }

    public static void clearExecutionObserver(String terminalSessionId, ExecutionObserver observer) {
        currentExecutionObserver.remove();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            executionObserversByTerminal.remove(terminalSessionId, observer);
        }
    }

    public Map<String, Object> executeCommand(
            @Annotations.Schema(name = "command", description = "要执行的 Shell 命令，如: ls -la, apt install docker.io, docker --version")
            String command) {

        String toolCallId = "call_" + UUID.randomUUID();
        long startedAt = System.currentTimeMillis();

        // 优先从 ThreadLocal 获取，支持异步线程继承
        String terminalSessionId = currentTerminalSession.get();

        // ThreadLocal 为空时回退到会话级变量（线程池场景下 ThreadLocal 可能失效）
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            terminalSessionId = sessionTerminalSessionId;
            log.info("[executeCommand] ThreadLocal 为空，回退到会话级变量: terminalSessionId={}", terminalSessionId);
        }

        log.info("[executeCommand] thread={}, terminalSessionId={}, command={}",
                Thread.currentThread().getName(), terminalSessionId, command);

        notifyObserver(terminalSessionId, ExecutionEvent.running(toolCallId, command, startedAt));

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            log.warn("[executeCommand] 终端会话ID为空，无法执行命令");
            return failureResult(toolCallId, terminalSessionId, command, startedAt,
                    "未绑定 SSH 终端会话。请先打开 SSH 终端连接。");
        }

        if (!sshTerminalService.sessionExists(terminalSessionId)) {
            log.warn("[executeCommand] 终端会话不存在: {}", terminalSessionId);
            return failureResult(toolCallId, terminalSessionId, command, startedAt,
                    "SSH 终端会话不存在或已关闭: " + terminalSessionId);
        }

        try {
            log.info("SSH 执行命令: session={}, command={}", terminalSessionId, command);

            // 执行命令
            String output = sshTerminalService.executeCommand(terminalSessionId, command);

            log.info("SSH 命令执行完成: outputLength={}, output={}",
                    output.length(), output.length() > 300 ? output.substring(0, 300) + "..." : output);

            // 分析输出，判断是否成功
            boolean success = isExecutionSuccessful(output);

            Map<String, Object> result = new HashMap<>();
            result.put("command", command);
            result.put("output", output);
            result.put("success", success);

            String suggestion = null;
            if (!success) {
                suggestion = analyzeError(output);
                result.put("suggestion", suggestion);
            }

            long completedAt = System.currentTimeMillis();
            notifyObserver(terminalSessionId, ExecutionEvent.completed(
                    toolCallId,
                    command,
                    success ? "success" : "error",
                    startedAt,
                    completedAt,
                    output.length(),
                    suggestion
            ));

            return result;

        } catch (Exception e) {
            log.error("SSH 命令执行异常: session={}, command={}", terminalSessionId, command, e);
            return failureResult(toolCallId, terminalSessionId, command, startedAt,
                    "命令执行异常: " + e.getMessage());
        }

    }

    private Map<String, Object> failureResult(
            String toolCallId,
            String terminalSessionId,
            String command,
            long startedAt,
            String message) {
        long completedAt = System.currentTimeMillis();
        notifyObserver(terminalSessionId, ExecutionEvent.completed(
                toolCallId,
                command,
                "error",
                startedAt,
                completedAt,
                0,
                message
        ));
        return Map.of(
                "success", false,
                "output", message,
                "command", command
        );
    }

    private void notifyObserver(String terminalSessionId, ExecutionEvent event) {
        ExecutionObserver observer = null;
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            observer = executionObserversByTerminal.get(terminalSessionId);
        }
        if (observer == null) {
            observer = currentExecutionObserver.get();
        }
        if (observer == null) {
            return;
        }
        try {
            observer.onExecutionEvent(event);
        } catch (Exception e) {
            log.warn("工具执行状态通知失败: toolCallId={}, reason={}", event.getToolCallId(), e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ExecutionObserver {
        void onExecutionEvent(ExecutionEvent event);
    }

    public static final class ExecutionEvent {
        private final String toolCallId;
        private final String toolName;
        private final String command;
        private final String status;
        private final long startedAt;
        private final long completedAt;
        private final long durationMs;
        private final int outputLength;
        private final String errorMessage;

        private ExecutionEvent(
                String toolCallId,
                String command,
                String status,
                long startedAt,
                long completedAt,
                int outputLength,
                String errorMessage) {
            this.toolCallId = toolCallId;
            this.toolName = "executeCommand";
            this.command = command;
            this.status = status;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.durationMs = completedAt > 0 ? Math.max(0, completedAt - startedAt) : 0;
            this.outputLength = outputLength;
            this.errorMessage = errorMessage;
        }

        public static ExecutionEvent running(String toolCallId, String command, long startedAt) {
            return new ExecutionEvent(toolCallId, command, "running", startedAt, 0, 0, null);
        }

        public static ExecutionEvent completed(
                String toolCallId,
                String command,
                String status,
                long startedAt,
                long completedAt,
                int outputLength,
                String errorMessage) {
            return new ExecutionEvent(
                    toolCallId,
                    command,
                    status,
                    startedAt,
                    completedAt,
                    outputLength,
                    errorMessage
            );
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getCommand() {
            return command;
        }

        public String getStatus() {
            return status;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public long getCompletedAt() {
            return completedAt;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public int getOutputLength() {
            return outputLength;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * 判断命令执行是否成功
     */
    private boolean isExecutionSuccessful(String output) {
        if (output == null || output.isEmpty()) {
            return true;
        }

        String lowerOutput = output.toLowerCase();
        String[] errorIndicators = {
                "command not found", "no such file or directory", "permission denied",
                "operation not permitted", "cannot find", "error:", "failed",
                "fatal:", "unable to", "connection refused", "network is unreachable"
        };

        for (String indicator : errorIndicators) {
            if (lowerOutput.contains(indicator)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 分析错误并提供解决建议
     */
    private String analyzeError(String output) {
        if (output == null) return null;

        String lowerOutput = output.toLowerCase();

        if (lowerOutput.contains("command not found")) {
            return "命令不存在。可能原因：命令拼写错误、软件未安装、或命令不在 PATH 中。建议检查命令名称或安装对应软件包。";
        }
        if (lowerOutput.contains("permission denied")) {
            return "权限不足。建议使用 sudo 提升权限，或检查文件/目录权限。";
        }
        if (lowerOutput.contains("no such file or directory")) {
            return "文件或目录不存在。建议检查路径是否正确，或使用绝对路径。";
        }
        if (lowerOutput.contains("connection refused") || lowerOutput.contains("network is unreachable")) {
            return "网络连接问题。建议检查网络连接、确认目标服务是否运行、检查防火墙设置。";
        }
        return "执行失败，请检查命令和输出信息。";
    }

}
