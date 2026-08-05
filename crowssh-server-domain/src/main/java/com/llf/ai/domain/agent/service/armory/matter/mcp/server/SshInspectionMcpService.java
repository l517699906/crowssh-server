package com.llf.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 面向 SSH Agent 的结构化只读诊断工具。
 */
@Slf4j
@Service
public class SshInspectionMcpService {

    private static final Pattern SERVICE_NAME_PATTERN =
            Pattern.compile("[A-Za-z0-9_.@:-]{1,128}");
    private static final int MAX_LOG_LINES = 200;

    private final SshExecuteAdkTool sshExecuteAdkTool;

    public SshInspectionMcpService(SshExecuteAdkTool sshExecuteAdkTool) {
        this.sshExecuteAdkTool = sshExecuteAdkTool;
    }

    @Tool(description = "读取当前绑定 SSH 主机的系统、负载、CPU、内存和文件系统摘要；只读，不修改远程状态")
    public Map<String, Object> inspectSystem() {
        String command = """
                printf '%s\\n' '=== operating-system ==='
                if [ -r /etc/os-release ]; then sed -n '1,12p' /etc/os-release; else uname -srm; fi
                printf '%s\\n' '=== kernel ==='
                uname -srm
                printf '%s\\n' '=== uptime-and-load ==='
                uptime
                printf '%s\\n' '=== cpu-count ==='
                if command -v nproc >/dev/null 2>&1; then nproc; else getconf _NPROCESSORS_ONLN 2>/dev/null || true; fi
                printf '%s\\n' '=== memory ==='
                if command -v free >/dev/null 2>&1; then free -h; elif command -v vm_stat >/dev/null 2>&1; then vm_stat; fi
                printf '%s\\n' '=== filesystems ==='
                df -hP 2>/dev/null || df -h
                """;
        return sshExecuteAdkTool.executeCommand(command);
    }

    @Tool(description = "检查当前绑定 SSH 主机上的 systemd 服务状态并读取最近日志；服务名和日志行数受限，只读")
    public Map<String, Object> inspectService(ServiceInspectionRequest request) {
        try {
            String serviceName = requireServiceName(request == null ? null : request.getServiceName());
            int logLines = bounded(
                    request == null ? null : request.getLogLines(), 80, 1, MAX_LOG_LINES, "日志行数");
            String unit = shellQuote(serviceName);
            String command = "command -v systemctl >/dev/null 2>&1 || { echo 'systemctl is unavailable'; exit 127; }; "
                    + "printf '%s\\n' '=== service-properties ==='; "
                    + "systemctl show --no-pager --property=Id,LoadState,ActiveState,SubState,UnitFileState,MainPID "
                    + unit + " 2>&1; properties_status=$?; "
                    + "printf '%s\\n' '=== service-status ==='; "
                    + "systemctl status --no-pager --lines=0 " + unit + " 2>&1; status_status=$?; "
                    + "printf '%s\\n' '=== recent-journal ==='; "
                    + "journalctl --no-pager -u " + unit + " -n " + logLines + " 2>&1; journal_status=$?; "
                    + "if [ \"$properties_status\" -ne 0 ]; then exit \"$properties_status\"; fi; "
                    + "if [ \"$status_status\" -ne 0 ]; then exit \"$status_status\"; fi; "
                    + "exit \"$journal_status\"";
            return sshExecuteAdkTool.executeCommand(command);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    @Tool(description = "列出当前绑定 SSH 主机的 TCP/UDP 监听端口，可按单个端口过滤；最多返回 200 行，只读")
    public Map<String, Object> inspectNetwork(NetworkInspectionRequest request) {
        try {
            Integer port = request == null ? null : request.getPort();
            if (port != null && (port < 1 || port > 65535)) {
                throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
            }

            String lineFilter;
            if (port != null) {
                lineFilter = "awk 'NR == 1 || $0 ~ /[:.]" + port
                        + "([[:space:]]|$)/' | sed -n '1,200p'";
            } else {
                lineFilter = "sed -n '1,200p'";
            }
            String command = "set -o pipefail; "
                    + "if command -v ss >/dev/null 2>&1; then ss -lntup 2>&1 | " + lineFilter + "; "
                    + "elif command -v netstat >/dev/null 2>&1; then netstat -an 2>&1 | " + lineFilter + "; "
                    + "else echo 'ss and netstat are unavailable'; exit 127; fi";
            return sshExecuteAdkTool.executeCommand(command);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    @Tool(description = "检查当前绑定 SSH 主机指定绝对路径所在文件系统的容量和 inode 使用情况；只读")
    public Map<String, Object> inspectDisk(DiskInspectionRequest request) {
        try {
            String path = request == null || request.getPath() == null || request.getPath().isBlank()
                    ? "/"
                    : request.getPath().trim();
            if (!(".".equals(path) || path.startsWith("/"))
                    || path.length() > 1024 || containsControlCharacter(path)) {
                throw new IllegalArgumentException("磁盘检查路径必须是长度不超过 1024 的绝对路径或 .");
            }

            String quotedPath = shellQuote(path);
            String command = "test -e " + quotedPath
                    + " || { echo 'path does not exist'; exit 2; }; "
                    + "printf '%s\\n' '=== disk-space ==='; df -hP " + quotedPath + " 2>&1; "
                    + "printf '%s\\n' '=== inode-usage ==='; df -iP " + quotedPath + " 2>&1 || true";
            return sshExecuteAdkTool.executeCommand(command);
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        }
    }

    private String requireServiceName(String value) {
        String serviceName = value == null ? "" : value.trim();
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            throw new IllegalArgumentException("服务名只能包含字母、数字、点、下划线、@、冒号和连字符");
        }
        return serviceName;
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) {
            throw new IllegalArgumentException(field + "必须在 " + min + " 到 " + max + " 之间");
        }
        return resolved;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private Map<String, Object> failure(String message) {
        log.warn("SSH 只读诊断参数被拒绝: {}", message);
        return Map.of("success", false, "error", message);
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServiceInspectionRequest {
        @JsonProperty(required = true, value = "serviceName")
        @JsonPropertyDescription("systemd 服务名，例如 nginx.service 或 docker")
        private String serviceName;

        @JsonProperty(value = "logLines", defaultValue = "80")
        @JsonPropertyDescription("最近日志行数，范围 1 到 200，默认 80")
        private Integer logLines = 80;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NetworkInspectionRequest {
        @JsonProperty("port")
        @JsonPropertyDescription("可选端口过滤条件，范围 1 到 65535；省略时返回全部监听端口")
        private Integer port;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DiskInspectionRequest {
        @JsonProperty(value = "path", defaultValue = "/")
        @JsonPropertyDescription("要检查的远程绝对路径，默认 /")
        private String path = "/";
    }
}
