package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.IServerMonitorPort;
import com.llf.ai.domain.ssh.adapter.port.ServerMonitorUnavailableException;
import com.llf.ai.domain.ssh.adapter.port.ServerMonitorUnsupportedException;
import com.llf.ai.domain.ssh.model.entity.ServerMonitorSnapshotEntity;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通过短生命周期 SSH exec channel 采集 Linux 主机快照。
 */
@Slf4j
@Component
public class ServerMonitorPort implements IServerMonitorPort {

    static final long COMMAND_TIMEOUT_MS = 3_000L;
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 1_000L;
    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private static final String UNSUPPORTED_MARKER = "__CROWSSH_MONITOR_UNSUPPORTED__";
    private static final String SECTION_PREFIX = "__CROWSSH_MONITOR_BEGIN_";
    private static final String SECTION_SUFFIX = "__";

    static final String SNAPSHOT_COMMAND = """
            export LC_ALL=C
            if [ "$(uname -s 2>/dev/null)" != "Linux" ] \
                    || [ ! -r /proc/stat ] \
                    || [ ! -r /proc/meminfo ] \
                    || [ ! -r /proc/loadavg ] \
                    || [ ! -r /proc/uptime ]; then
              printf '%s\n' '__CROWSSH_MONITOR_UNSUPPORTED__'
              exit 64
            fi
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_HOST__'
            hostname 2>/dev/null || uname -n
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_OS_RELEASE__'
            if [ -r /etc/os-release ]; then sed -n '1,64p' /etc/os-release; fi
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_KERNEL__'
            uname -r
            uname -m
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_UPTIME__'
            cat /proc/uptime
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_CPU__'
            sed -n '1p' /proc/stat
            if command -v getconf >/dev/null 2>&1; then
              getconf _NPROCESSORS_ONLN 2>/dev/null
            else
              awk '/^processor[[:space:]]*:/ { count++ } END { print count + 0 }' /proc/cpuinfo
            fi
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_LOAD__'
            cat /proc/loadavg
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_MEMORY__'
            sed -n '/^MemTotal:/p;/^MemAvailable:/p;/^MemFree:/p;/^Buffers:/p;/^Cached:/p' /proc/meminfo
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_DISK__'
            df -Pk / 2>/dev/null | tail -n 1
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_ROUTE__'
            if [ -r /proc/net/route ]; then
              awk '$2 == "00000000" && $1 != "Iface" { print $1; exit }' /proc/net/route
            fi
            printf '%s\n' '__CROWSSH_MONITOR_BEGIN_NETWORK__'
            if [ -r /proc/net/dev ]; then cat /proc/net/dev; fi
            """;

    private final SshSessionPort sshSessionPort;

    public ServerMonitorPort(SshSessionPort sshSessionPort) {
        this.sshSessionPort = sshSessionPort;
    }

    @Override
    public ServerMonitorSnapshotEntity collectSnapshot(String connectionId) {
        Session session = null;
        Session.Command command = null;
        Thread stdoutReader = null;
        Thread stderrReader = null;
        BoundedOutput stdout = new BoundedOutput(MAX_OUTPUT_BYTES);
        BoundedOutput stderr = new BoundedOutput(MAX_OUTPUT_BYTES);
        try {
            session = sshSessionPort.openSession(connectionId);
            command = session.exec(SNAPSHOT_COMMAND);
            stdoutReader = startReader(command.getInputStream(), stdout, "stdout", connectionId);
            stderrReader = startReader(command.getErrorStream(), stderr, "stderr", connectionId);

            command.join(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (command.isOpen()) {
                signalTermination(command, connectionId);
                throw new ServerMonitorUnavailableException("服务器监控采集超时");
            }

            closeQuietly(command, "监控命令", connectionId);
            joinReaders(stdoutReader, stderrReader);
            if (stdout.truncated() || stderr.truncated()) {
                throw new ServerMonitorUnavailableException("服务器监控输出超过限制");
            }

            String output = stdout.text();
            Integer exitStatus = command.getExitStatus();
            if (output.contains(UNSUPPORTED_MARKER) || Integer.valueOf(64).equals(exitStatus)) {
                throw new ServerMonitorUnsupportedException("远端主机不是受支持的 Linux procfs 环境");
            }
            if (exitStatus == null || exitStatus != 0) {
                log.warn("服务器监控命令失败 connectionId={} exitStatus={} stderrLength={}",
                        connectionId, exitStatus, stderr.size());
                throw new ServerMonitorUnavailableException("服务器监控采集命令执行失败");
            }
            return parseSnapshot(output, System.currentTimeMillis());
        } catch (ServerMonitorUnsupportedException | ServerMonitorUnavailableException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServerMonitorUnavailableException("创建服务器监控 SSH 通道失败", exception);
        } catch (RuntimeException exception) {
            throw new ServerMonitorUnavailableException("服务器监控采集失败", exception);
        } finally {
            closeQuietly(command, "监控命令", connectionId);
            closeQuietly(session, "监控会话", connectionId);
            joinReader(stdoutReader);
            joinReader(stderrReader);
        }
    }

    static ServerMonitorSnapshotEntity parseSnapshot(String output, long capturedAtEpochMs) {
        Map<String, List<String>> sections = splitSections(output);
        String hostname = firstLine(sections, "HOST", "主机名");
        Map<String, String> osRelease = parseKeyValues(sections.get("OS_RELEASE"));
        String osName = firstNonBlank(
                unquote(osRelease.get("PRETTY_NAME")),
                unquote(osRelease.get("NAME")),
                "Linux");
        String osVersion = blankToNull(unquote(osRelease.get("VERSION_ID")));

        List<String> kernel = requireSection(sections, "KERNEL");
        if (kernel.size() < 2) {
            throw unavailable("内核信息不完整");
        }
        String kernelVersion = requireText(kernel.get(0), "内核版本");
        String architecture = requireText(kernel.get(1), "系统架构");

        String[] uptimeFields = firstLine(sections, "UPTIME", "运行时长").split("\\s+");
        long uptimeSeconds = (long) parseDouble(uptimeFields[0], "运行时长");

        List<String> cpuLines = requireSection(sections, "CPU");
        if (cpuLines.size() < 2) {
            throw unavailable("CPU 信息不完整");
        }
        String[] cpuFields = cpuLines.get(0).trim().split("\\s+");
        if (cpuFields.length < 5 || !"cpu".equals(cpuFields[0])) {
            throw unavailable("CPU 计数格式无效");
        }
        long totalTicks = 0;
        int lastTotalField = Math.min(cpuFields.length - 1, 8);
        for (int index = 1; index <= lastTotalField; index++) {
            totalTicks = Math.addExact(totalTicks, parseLong(cpuFields[index], "CPU 计数"));
        }
        long idleTicks = parseLong(cpuFields[4], "CPU 空闲计数");
        if (cpuFields.length > 5) {
            idleTicks = Math.addExact(idleTicks, parseLong(cpuFields[5], "CPU 等待计数"));
        }
        int logicalProcessors = parsePositiveInt(cpuLines.get(1), "逻辑处理器数量");

        String[] loadFields = firstLine(sections, "LOAD", "系统负载").split("\\s+");
        if (loadFields.length < 3) {
            throw unavailable("系统负载格式无效");
        }
        ServerMonitorSnapshotEntity.Load load = new ServerMonitorSnapshotEntity.Load(
                parseDouble(loadFields[0], "1 分钟负载"),
                parseDouble(loadFields[1], "5 分钟负载"),
                parseDouble(loadFields[2], "15 分钟负载"));

        Map<String, Long> memoryKb = parseMemory(sections.get("MEMORY"));
        long totalMemory = memoryBytes(memoryKb, "MemTotal");
        Long availableKb = memoryKb.get("MemAvailable");
        if (availableKb == null) {
            availableKb = Math.addExact(
                    Math.addExact(memoryKb.getOrDefault("MemFree", 0L),
                            memoryKb.getOrDefault("Buffers", 0L)),
                    memoryKb.getOrDefault("Cached", 0L));
        }
        long availableMemory = Math.multiplyExact(availableKb, 1024L);

        ServerMonitorSnapshotEntity.Disk disk = parseDisk(sections.get("DISK"));
        ServerMonitorSnapshotEntity.Network network = parseNetwork(
                sections.get("NETWORK"), firstOptionalLine(sections.get("ROUTE")));

        return new ServerMonitorSnapshotEntity(
                capturedAtEpochMs,
                new ServerMonitorSnapshotEntity.Host(
                        hostname, osName, osVersion, kernelVersion, architecture),
                Math.max(0, uptimeSeconds),
                new ServerMonitorSnapshotEntity.Cpu(
                        logicalProcessors, totalTicks, idleTicks),
                load,
                new ServerMonitorSnapshotEntity.Memory(
                        totalMemory, Math.min(totalMemory, Math.max(0, availableMemory))),
                disk,
                network);
    }

    private static Map<String, List<String>> splitSections(String output) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : output.replace("\r", "").split("\n")) {
            if (line.startsWith(SECTION_PREFIX) && line.endsWith(SECTION_SUFFIX)) {
                String name = line.substring(
                        SECTION_PREFIX.length(), line.length() - SECTION_SUFFIX.length());
                current = sections.computeIfAbsent(name, ignored -> new ArrayList<>());
            } else if (current != null && !line.isBlank()) {
                current.add(line.trim());
            }
        }
        return sections;
    }

    private static Map<String, String> parseKeyValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        if (lines == null) {
            return values;
        }
        for (String line : lines) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return values;
    }

    private static Map<String, Long> parseMemory(List<String> lines) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (lines != null) {
            for (String line : lines) {
                String[] fields = line.split("\\s+");
                if (fields.length >= 2) {
                    values.put(fields[0].replace(":", ""), parseLong(fields[1], "内存计数"));
                }
            }
        }
        return values;
    }

    private static long memoryBytes(Map<String, Long> memoryKb, String field) {
        Long value = memoryKb.get(field);
        if (value == null || value <= 0) {
            throw unavailable("内存信息不完整");
        }
        return Math.multiplyExact(value, 1024L);
    }

    private static ServerMonitorSnapshotEntity.Disk parseDisk(List<String> lines) {
        String line = firstOptionalLine(lines);
        if (line == null) {
            return null;
        }
        String[] fields = line.split("\\s+");
        if (fields.length < 6) {
            return null;
        }
        try {
            int totalIndex = fields.length - 5;
            long total = Math.multiplyExact(Long.parseLong(fields[totalIndex]), 1024L);
            long used = Math.multiplyExact(Long.parseLong(fields[totalIndex + 1]), 1024L);
            long available = Math.multiplyExact(Long.parseLong(fields[totalIndex + 2]), 1024L);
            return new ServerMonitorSnapshotEntity.Disk(
                    fields[fields.length - 1], total, used, available);
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static ServerMonitorSnapshotEntity.Network parseNetwork(
            List<String> lines,
            String defaultInterface) {
        if (lines == null) {
            return null;
        }
        List<ServerMonitorSnapshotEntity.Network> interfaces = new ArrayList<>();
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            if ("lo".equals(name)) {
                continue;
            }
            String[] fields = line.substring(separator + 1).trim().split("\\s+");
            if (fields.length < 9) {
                continue;
            }
            try {
                interfaces.add(new ServerMonitorSnapshotEntity.Network(
                        name, Long.parseLong(fields[0]), Long.parseLong(fields[8])));
            } catch (NumberFormatException ignored) {
                // 忽略单个格式异常的网卡行。
            }
        }
        if (interfaces.isEmpty()) {
            return null;
        }
        if (defaultInterface != null) {
            for (ServerMonitorSnapshotEntity.Network candidate : interfaces) {
                if (defaultInterface.equals(candidate.interfaceName())) {
                    return candidate;
                }
            }
        }
        if (interfaces.size() == 1) {
            return interfaces.get(0);
        }
        long received = 0;
        long transmitted = 0;
        for (ServerMonitorSnapshotEntity.Network candidate : interfaces) {
            received = saturatedAdd(received, candidate.receivedBytes());
            transmitted = saturatedAdd(transmitted, candidate.transmittedBytes());
        }
        return new ServerMonitorSnapshotEntity.Network("all", received, transmitted);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String firstLine(
            Map<String, List<String>> sections,
            String section,
            String field) {
        return requireText(firstOptionalLine(requireSection(sections, section)), field);
    }

    private static List<String> requireSection(Map<String, List<String>> sections, String name) {
        List<String> lines = sections.get(name);
        if (lines == null || lines.isEmpty()) {
            throw unavailable("监控输出缺少 " + name + " 分段");
        }
        return lines;
    }

    private static String firstOptionalLine(List<String> lines) {
        return lines == null || lines.isEmpty() ? null : blankToNull(lines.get(0));
    }

    private static String requireText(String value, String field) {
        String resolved = blankToNull(value);
        if (resolved == null) {
            throw unavailable(field + "为空");
        }
        return resolved;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String unquote(String value) {
        String resolved = blankToNull(value);
        if (resolved == null || resolved.length() < 2) {
            return resolved;
        }
        char first = resolved.charAt(0);
        char last = resolved.charAt(resolved.length() - 1);
        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
            return resolved.substring(1, resolved.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return resolved;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw unavailable(field + "格式无效");
        }
    }

    private static int parsePositiveInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw unavailable(field + "必须大于 0");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw unavailable(field + "格式无效");
        }
    }

    private static double parseDouble(String value, String field) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < 0) {
                throw unavailable(field + "格式无效");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw unavailable(field + "格式无效");
        }
    }

    private static ServerMonitorUnavailableException unavailable(String message) {
        return new ServerMonitorUnavailableException(message);
    }

    private Thread startReader(
            InputStream input,
            BoundedOutput output,
            String streamName,
            String connectionId) {
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[4096];
            try {
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    output.write(buffer, length);
                }
            } catch (IOException exception) {
                log.debug("读取监控{}失败 connectionId={} reason={}",
                        streamName, connectionId, exception.getMessage());
            }
        }, "server-monitor-" + streamName + "-" + connectionId);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void joinReaders(Thread stdoutReader, Thread stderrReader) {
        long deadline = System.currentTimeMillis() + OUTPUT_DRAIN_TIMEOUT_MS;
        joinReader(stdoutReader, deadline);
        joinReader(stderrReader, deadline);
    }

    private void joinReader(Thread reader) {
        joinReader(reader, System.currentTimeMillis() + OUTPUT_DRAIN_TIMEOUT_MS);
    }

    private void joinReader(Thread reader, long deadline) {
        if (reader == null) {
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return;
        }
        try {
            reader.join(remaining);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void signalTermination(Session.Command command, String connectionId) {
        try {
            command.signal(Signal.TERM);
            command.join(250, TimeUnit.MILLISECONDS);
        } catch (IOException | RuntimeException exception) {
            log.debug("终止监控命令失败 connectionId={} reason={}",
                    connectionId, exception.getMessage());
        }
    }

    private void closeQuietly(Closeable closeable, String resourceName, String connectionId) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException exception) {
            log.debug("关闭{}失败 connectionId={} reason={}",
                    resourceName, connectionId, exception.getMessage());
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean(false);

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        private synchronized void write(byte[] bytes, int length) {
            int remaining = limit - output.size();
            if (remaining > 0) {
                output.write(bytes, 0, Math.min(length, remaining));
            }
            if (length > remaining) {
                truncated.set(true);
            }
        }

        private synchronized String text() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private synchronized int size() {
            return output.size();
        }

        private boolean truncated() {
            return truncated.get();
        }
    }
}
