package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.ToolContext;
import com.llf.ai.domain.ssh.adapter.port.CommandExecutionResult;
import com.llf.ai.domain.ssh.adapter.port.TerminalSessionEntity;
import com.llf.ai.domain.ssh.service.ISshTerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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

    public static final String TERMINAL_SESSION_ID_STATE_KEY = "crowssh:terminalSessionId";
    public static final String CONNECTION_ID_STATE_KEY = "crowssh:connectionId";
    public static final String OWNER_ID_STATE_KEY = "crowssh:ownerId";

    private final ISshTerminalService sshTerminalService;
    private final CommandApprovalService commandApprovalService;

    public SshExecuteAdkTool(
            ISshTerminalService sshTerminalService,
            CommandApprovalService commandApprovalService
    ) {
        this.sshTerminalService = sshTerminalService;
        this.commandApprovalService = commandApprovalService;
    }

    // google-adk-spring-ai 1.2.0 转换工具时不会传递 ToolContext，因此由请求线程显式绑定执行资源。
    private static final ThreadLocal<ExecutionBinding> currentExecutionBinding = new ThreadLocal<>();

    // 危险命令模式（需要用户确认），这些命令，也可以设计成配置来使用
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
            "(?:\\brm\\s+-rf\\s+/|\\bdd\\s+if=|\\bmkfs\\.|:\\(\\)\\s*\\{|>\\s*/dev/sd|\\bchmod\\s+-R\\s+777\\s+/)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 设置当前线程的终端会话 ID（兼容旧接口）
     */
    public static void setCurrentTerminalSession(String terminalSessionId) {
        setCurrentExecutionContext(null, terminalSessionId, null, null);
    }

    /**
     * 绑定当前 AI 请求使用的 SSH 资源和会话。
     */
    public static void setCurrentExecutionContext(String ownerId,
                                                  String terminalSessionId,
                                                  String connectionId,
                                                  String agentSessionId) {
        currentExecutionBinding.set(
                new ExecutionBinding(ownerId, terminalSessionId, connectionId, agentSessionId));
        log.info("[ThreadLocal] 设置终端会话: thread={}, terminalSession={}",
                Thread.currentThread().getName(), terminalSessionId);
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    public static void clearCurrentTerminalSession() {
        currentExecutionBinding.remove();
    }

    /**
     * 获取当前工具调用线程绑定的可信 SSH 上下文。
     */
    public static ExecutionBinding requireCurrentExecutionBinding() {
        ExecutionBinding binding = currentExecutionBinding.get();
        if (binding == null
                || binding.ownerId() == null || binding.ownerId().isBlank()
                || binding.terminalSessionId() == null || binding.terminalSessionId().isBlank()
                || binding.connectionId() == null || binding.connectionId().isBlank()) {
            throw new IllegalStateException("AI 请求未绑定可信 SSH 资源，请先打开正确的 SSH 终端");
        }
        return binding;
    }

    static String currentAgentSessionId() {
        ExecutionBinding binding = currentExecutionBinding.get();
        return binding == null ? null : binding.agentSessionId();
    }

    @Annotations.Schema(
            name = "executeCommand",
            description = "在当前 AI 对话绑定的 SSH 连接上通过独立通道执行一条 Shell 命令；每次调用状态隔离"
    )
    public Map<String, Object> executeCommandWithContext(
            @Annotations.Schema(name = "command", description = "要执行的完整 Shell 命令；cd、export 等状态不会保留到下一次调用")
            String command,
            @Annotations.Schema(name = "toolContext", description = "ADK 工具调用上下文")
            ToolContext toolContext) {
        if (toolContext == null) {
            return executeCommand(command);
        }
        String terminalSessionId = stateValue(toolContext, TERMINAL_SESSION_ID_STATE_KEY);
        String connectionId = stateValue(toolContext, CONNECTION_ID_STATE_KEY);
        String ownerId = stateValue(toolContext, OWNER_ID_STATE_KEY);
        return executeBoundCommand(
                command, ownerId, terminalSessionId, connectionId, toolContext.sessionId());
    }

    /**
     * case 层同步直调的兼容入口。没有线程绑定时直接拒绝，不再回退到全局会话。
     */
    @Annotations.Schema(
            name = "executeCommand",
            description = "在当前 AI 对话绑定的 SSH 连接上通过独立通道执行一条 Shell 命令；每次调用状态隔离"
    )
    public Map<String, Object> executeCommand(
            @Annotations.Schema(name = "command", description = "要执行的完整 Shell 命令；cd、export 等状态不会保留到下一次调用")
            String command) {
        ExecutionBinding binding = currentExecutionBinding.get();
        String ownerId = binding == null ? null : binding.ownerId();
        String terminalSessionId = binding == null ? null : binding.terminalSessionId();
        TerminalSessionEntity terminalSession = terminalSessionId == null
                ? null
                : sshTerminalService.getTerminalSession(ownerId, terminalSessionId);
        String connectionId = binding == null ? null : binding.connectionId();
        if ((connectionId == null || connectionId.isBlank()) && terminalSession != null) {
            connectionId = terminalSession.getConnectionId();
        }
        String agentSessionId = binding == null ? null : binding.agentSessionId();
        return executeBoundCommand(
                command, ownerId, terminalSessionId, connectionId, agentSessionId);
    }

    public record ExecutionBinding(
            String ownerId,
            String terminalSessionId,
            String connectionId,
            String agentSessionId
    ) {
    }

    private Map<String, Object> executeBoundCommand(
            String command,
            String ownerId,
            String terminalSessionId,
            String connectionId,
            String agentSessionId
    ) {

        String toolCallId = "call_" + UUID.randomUUID();
        long startedAt = System.currentTimeMillis();
        String safeCommand = command == null ? "" : command;
        String commandHash = CommandApprovalService.commandHash(safeCommand);
        Map<String, Object> arguments = Map.of("command", safeCommand);

        log.info("[executeCommand] request sessionId={} terminalSessionId={} connectionId={} commandHash={} commandLength={}",
                agentSessionId, terminalSessionId, connectionId, commandHash, safeCommand.length());

        if (ownerId == null || ownerId.isBlank()) {
            log.warn("[executeCommand] 设备身份为空，拒绝执行命令");
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "AI 请求缺少可信设备身份，无法执行 SSH 命令。");
        }

        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            log.warn("[executeCommand] 终端会话ID为空，无法执行命令");
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "未绑定 SSH 终端会话。请先打开 SSH 终端连接。");
        }
        if (safeCommand.isBlank()) {
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "命令不能为空。");
        }

        TerminalSessionEntity terminalSession = sshTerminalService.getTerminalSession(
                ownerId, terminalSessionId);
        if (terminalSession == null || !terminalSession.isActive()) {
            log.warn("[executeCommand] 终端会话不存在: {}", terminalSessionId);
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "SSH 终端会话不存在或已关闭: " + terminalSessionId);
        }
        if (connectionId == null || connectionId.isBlank()
                || !connectionId.equals(terminalSession.getConnectionId())) {
            log.warn("[executeCommand] 终端归属校验失败: terminalSessionId={}, expectedConnectionId={}, actualConnectionId={}",
                    terminalSessionId, connectionId, terminalSession.getConnectionId());
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "AI 对话绑定的服务器与当前 SSH 终端不一致");
        }

        if (DANGEROUS_PATTERN.matcher(safeCommand).find()) {
            log.warn("[executeCommand] 危险命令被硬性拦截 sessionId={} terminalSessionId={} commandHash={} commandLength={}",
                    agentSessionId, terminalSessionId, commandHash, safeCommand.length());
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "危险命令被硬性拦截。该命令可能导致系统损坏或数据丢失；如确需执行，请手动在终端操作。");
        }

        if (!ToolExecutionObserverRegistry.hasObserver(agentSessionId)) {
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, "error",
                    "当前请求不支持交互审批，AI SSH 命令未执行。");
        }

        CommandApprovalService.ApprovalTicket approvalTicket = null;
        try {
            approvalTicket = commandApprovalService.request(
                    ownerId,
                    agentSessionId,
                    terminalSessionId,
                    connectionId,
                    toolCallId,
                    safeCommand);
            ToolExecutionObserverRegistry.publish(
                    agentSessionId,
                    ToolExecutionEvent.approvalRequired(
                            toolCallId,
                            "executeCommand",
                            arguments,
                            startedAt,
                            approvalTicket.approvalId(),
                            approvalTicket.expiresAt(),
                            approvalTicket.riskLevel()));

            CommandApprovalService.Decision decision =
                    commandApprovalService.awaitDecision(approvalTicket);
            if (decision != CommandApprovalService.Decision.APPROVED) {
                String status = decision.name().toLowerCase();
                String message = switch (decision) {
                    case DENIED -> "用户已拒绝执行该命令。";
                    case EXPIRED -> "命令审批已过期，未执行。";
                    case CANCELLED -> "命令执行已取消。";
                    default -> "命令未获批准，未执行。";
                };
                commandApprovalService.recordExecutionResult(
                        approvalTicket, status, null, false, 0);
                return failureResult(
                        toolCallId, agentSessionId, safeCommand, startedAt, status, message);
            }

            ToolExecutionObserverRegistry.publish(
                    agentSessionId,
                    ToolExecutionEvent.running(
                            toolCallId, "executeCommand", arguments, startedAt));
            log.info("SSH 执行已批准 sessionId={} commandHash={} commandLength={}",
                    terminalSessionId, commandHash, safeCommand.length());

            // AI 命令通过隔离执行协议返回真实退出码，避免仅凭输出文本猜测成功与否。
            CommandExecutionResult execution = sshTerminalService.executeCommandWithResult(
                    ownerId, terminalSessionId, safeCommand);
            String output = execution.output();

            if (commandApprovalService.isCancelled(approvalTicket)
                    || Thread.currentThread().isInterrupted()) {
                commandApprovalService.recordExecutionResult(
                        approvalTicket, "cancelled", null, false, output.length());
                return failureResult(
                        toolCallId, agentSessionId, safeCommand, startedAt, "cancelled",
                        "命令执行已取消。");
            }

            log.info("SSH 命令执行完成: commandHash={} outputLength={} exitCode={} timedOut={} exitCodeKnown={}",
                    commandHash, output.length(), execution.exitCode(), execution.timedOut(),
                    execution.exitCodeKnown());

            // 只有 SSH exec channel 明确返回 0 才能判定成功，未知退出码不能冒充成功。
            boolean success = execution.isSuccess();

            Map<String, Object> result = new HashMap<>();
            result.put("command", safeCommand);
            result.put("output", output);
            result.put("success", success);
            result.put("exitCode", execution.exitCode());
            result.put("timedOut", execution.timedOut());
            result.put("exitCodeKnown", execution.exitCodeKnown());

            String suggestion = null;
            if (!success) {
                if (execution.timedOut()) {
                    suggestion = "命令执行超时，独立执行通道已终止，交互终端仍保持打开。";
                } else if (!execution.exitCodeKnown()) {
                    suggestion = "SSH 服务端未返回明确退出码，不能确认命令成功，请检查连接和命令输出。";
                } else {
                    suggestion = analyzeError(output);
                }
                result.put("suggestion", suggestion);
            }

            long completedAt = System.currentTimeMillis();
            ToolExecutionObserverRegistry.publish(
                    agentSessionId,
                    ToolExecutionEvent.completed(
                            toolCallId,
                            "executeCommand",
                            arguments,
                            result,
                            success ? "success" : "error",
                            startedAt,
                            completedAt,
                            output.length(),
                            suggestion
                    )
            );
            commandApprovalService.recordExecutionResult(
                    approvalTicket,
                    success ? "success" : "error",
                    execution.exitCode(),
                    execution.exitCodeKnown(),
                    output.length());

            return result;

        } catch (Exception e) {
            boolean cancelled = approvalTicket != null
                    && (commandApprovalService.isCancelled(approvalTicket)
                    || Thread.currentThread().isInterrupted());
            String status = cancelled ? "cancelled" : "error";
            log.error("SSH 命令执行异常 session={} commandHash={} commandLength={} status={} exceptionType={}",
                    terminalSessionId, commandHash, safeCommand.length(), status,
                    e.getClass().getSimpleName());
            if (approvalTicket != null) {
                commandApprovalService.recordExecutionResult(
                        approvalTicket, status, null, false, 0);
            }
            return failureResult(toolCallId, agentSessionId, safeCommand, startedAt, status,
                    cancelled ? "命令执行已取消。" : "命令执行异常，请检查 SSH 连接后重试。");
        } finally {
            if (approvalTicket != null) {
                commandApprovalService.complete(approvalTicket);
            }
        }

    }

    private Map<String, Object> failureResult(
            String toolCallId,
            String agentSessionId,
            String command,
            long startedAt,
            String status,
            String message) {
        long completedAt = System.currentTimeMillis();
        Map<String, Object> result = Map.of(
                "success", false,
                "output", message,
                "command", command == null ? "" : command
        );
        ToolExecutionObserverRegistry.publish(
                agentSessionId,
                ToolExecutionEvent.completed(
                        toolCallId,
                        "executeCommand",
                        Map.of("command", command == null ? "" : command),
                        result,
                        status,
                        startedAt,
                        completedAt,
                        0,
                        message
                )
        );
        return result;
    }

    private static String stateValue(ToolContext toolContext, String key) {
        Object value = toolContext.state().get(key);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
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
