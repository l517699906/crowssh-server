package com.llf.ai.domain.agent.service.armory.matter.mcp.server;

import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import com.llf.ai.domain.ssh.adapter.port.SftpFileEntity;
import com.llf.ai.domain.ssh.service.ISftpService;
import com.llf.ai.domain.ssh.service.sftp.SftpTextContentEntity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 面向 SSH Agent 的只读 SFTP 工具。
 * <p>
 * 所有 owner/connection 信息都从当前请求绑定读取，模型不能自行指定目标主机。
 */
@Slf4j
@Service
public class SshRemoteFileMcpService {

    private static final int DEFAULT_LIST_LIMIT = 100;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int DEFAULT_READ_CHARS = 20_000;
    private static final int MAX_READ_CHARS = 50_000;
    private static final int MAX_PATH_LENGTH = 2048;
    private static final String REMOTE_FILE_FAILURE_MESSAGE = "远程文件工具执行失败，请稍后重试。";

    private final ISftpService sftpService;

    public SshRemoteFileMcpService(ISftpService sftpService) {
        this.sftpService = sftpService;
    }

    @Tool(description = "列出当前绑定 SSH 主机目录中的远程文件和目录，只读且最多返回 200 项")
    public Map<String, Object> listRemoteFiles(ListRemoteFilesRequest request) {
        try {
            SshExecuteAdkTool.ExecutionBinding binding = binding();
            String path = safePath(request == null ? null : request.getPath(), ".");
            int limit = bounded(request == null ? null : request.getLimit(),
                    DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT, "返回数量");
            List<SftpFileEntity> entries = sftpService.list(
                    binding.ownerId(), binding.connectionId(), path);
            List<SftpFileEntity> limited = entries == null
                    ? List.of()
                    : entries.stream()
                    .sorted(Comparator.comparing(SftpFileEntity::isDirectory).reversed()
                            .thenComparing(SftpFileEntity::getName,
                                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .limit(limit)
                    .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("path", path);
            result.put("entries", limited);
            result.put("total", entries == null ? 0 : entries.size());
            result.put("truncated", entries != null && entries.size() > limited.size());
            return result;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Tool(description = "读取当前绑定 SSH 主机的远程文本文件，支持分页并自动限制返回字符数；凭据和私钥路径会被拒绝")
    public Map<String, Object> readRemoteText(ReadRemoteTextRequest request) {
        try {
            SshExecuteAdkTool.ExecutionBinding binding = binding();
            String path = resolveSafeContentPath(
                    binding, request == null ? null : request.getPath());
            int offset = nonNegative(request == null ? null : request.getOffset(), 0, "偏移量");
            int maxChars = bounded(request == null ? null : request.getMaxChars(),
                    DEFAULT_READ_CHARS, 100, MAX_READ_CHARS, "返回字符数");
            SftpTextContentEntity text = readText(binding, path);
            String content = text.getContent() == null ? "" : text.getContent();
            if (offset > content.length()) {
                throw new IllegalArgumentException("偏移量超过文件长度");
            }
            int end = Math.min(content.length(), offset + maxChars);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("path", text.getPath());
            result.put("content", content.substring(offset, end));
            result.put("offset", offset);
            result.put("totalChars", content.length());
            result.put("truncated", end < content.length());
            if (end < content.length()) {
                result.put("nextOffset", end);
            }
            result.put("version", text.getVersion());
            result.put("encoding", text.getEncoding());
            result.put("lineEnding", text.getLineEnding());
            result.put("size", text.getSize());
            result.put("modifiedAt", text.getModifiedAt());
            return result;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Tool(description = "读取远程文件元数据（规范化路径、大小、目录标志、权限和修改时间），不读取文件内容")
    public Map<String, Object> statRemotePath(StatRemotePathRequest request) {
        try {
            SshExecuteAdkTool.ExecutionBinding binding = binding();
            String requestedPath = safePath(request == null ? null : request.getPath(), ".");
            String canonicalPath = sftpService.resolvePath(
                    binding.ownerId(), binding.connectionId(), requestedPath);
            if (canonicalPath == null || canonicalPath.isBlank()) {
                throw new IllegalStateException("远程路径无法规范化");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("path", canonicalPath);
            if ("/".equals(canonicalPath)) {
                result.put("directory", true);
                result.put("size", 0L);
                return result;
            }

            String parent = parentPath(canonicalPath);
            String name = fileName(canonicalPath);
            List<SftpFileEntity> entries = sftpService.list(
                    binding.ownerId(), binding.connectionId(), parent);
            SftpFileEntity entry = entries == null ? null : entries.stream()
                    .filter(item -> canonicalPath.equals(item.getPath())
                            || name.equals(item.getName()))
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                throw new IllegalStateException("远程路径不存在或无法读取元数据");
            }
            result.put("name", entry.getName());
            result.put("directory", entry.isDirectory());
            result.put("size", entry.getSize());
            result.put("permissions", entry.getPermissions());
            result.put("modifiedAt", entry.getModifiedAt());
            return result;
        } catch (Exception e) {
            return failure(e);
        }
    }

    @Tool(description = "比较当前绑定 SSH 主机上的两个远程文本文件，返回版本、大小和首个差异位置；不修改文件")
    public Map<String, Object> compareRemoteTexts(CompareRemoteTextsRequest request) {
        try {
            SshExecuteAdkTool.ExecutionBinding binding = binding();
            String leftPath = resolveSafeContentPath(
                    binding, request == null ? null : request.getLeftPath());
            String rightPath = resolveSafeContentPath(
                    binding, request == null ? null : request.getRightPath());
            int excerptChars = bounded(request == null ? null : request.getExcerptChars(),
                    500, 100, 2_000, "差异摘要字符数");
            SftpTextContentEntity left = readText(binding, leftPath);
            SftpTextContentEntity right = readText(binding, rightPath);
            String leftContent = left.getContent() == null ? "" : left.getContent();
            String rightContent = right.getContent() == null ? "" : right.getContent();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("leftPath", leftPath);
            result.put("rightPath", rightPath);
            result.put("leftVersion", left.getVersion());
            result.put("rightVersion", right.getVersion());
            result.put("leftSize", left.getSize());
            result.put("rightSize", right.getSize());
            result.put("equal", leftContent.equals(rightContent));
            if (!leftContent.equals(rightContent)) {
                int difference = firstDifference(leftContent, rightContent);
                result.put("firstDifferenceOffset", difference);
                result.put("leftExcerpt", excerpt(leftContent, difference, excerptChars));
                result.put("rightExcerpt", excerpt(rightContent, difference, excerptChars));
            }
            return result;
        } catch (Exception e) {
            return failure(e);
        }
    }

    private SshExecuteAdkTool.ExecutionBinding binding() {
        return SshExecuteAdkTool.requireCurrentExecutionBinding();
    }

    private SftpTextContentEntity readText(
            SshExecuteAdkTool.ExecutionBinding binding,
            String path) {
        return sftpService.readText(binding.ownerId(), binding.connectionId(), path);
    }

    private String resolveSafeContentPath(
            SshExecuteAdkTool.ExecutionBinding binding,
            String value) {
        String requestedPath = safeContentPath(value);
        String canonicalPath = sftpService.resolvePath(
                binding.ownerId(), binding.connectionId(), requestedPath);
        if (canonicalPath == null || canonicalPath.isBlank()) {
            throw new IllegalStateException("远程路径无法规范化");
        }
        return safeContentPath(canonicalPath);
    }

    private String safeContentPath(String value) {
        String path = safePath(value, null);
        if (isSensitivePath(path)) {
            throw new IllegalArgumentException("该路径可能包含凭据、私钥或环境秘密，禁止通过 AI 读取");
        }
        return path;
    }

    private String safePath(String value, String defaultValue) {
        String path = value == null || value.isBlank() ? defaultValue : value.trim();
        if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("远程路径不能为空且长度不能超过 " + MAX_PATH_LENGTH + "");
        }
        return path.replace('\\', '/');
    }

    private boolean isSensitivePath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        String name = fileName(normalized);
        return name.equals(".env") || name.startsWith(".env.")
                || name.equals("shadow") || name.equals("gshadow")
                || name.equals("credentials") || name.equals("config.json")
                || name.matches("id_(rsa|dsa|ecdsa|ed25519)")
                || name.endsWith(".pem") || name.endsWith(".key")
                || name.endsWith(".p12") || name.endsWith(".pfx")
                || normalized.contains("/.ssh/") && name.startsWith("id_")
                || normalized.matches(".*/proc/[^/]+/environ");
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < min || resolved > max) {
            throw new IllegalArgumentException(field + "必须在 " + min + " 到 " + max + " 之间");
        }
        return resolved;
    }

    private int nonNegative(Integer value, int defaultValue, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < 0 || resolved > 2_000_000) {
            throw new IllegalArgumentException(field + "必须在 0 到 2000000 之间");
        }
        return resolved;
    }

    private int firstDifference(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int index = 0; index < max; index++) {
            if (left.charAt(index) != right.charAt(index)) {
                return index;
            }
        }
        return max;
    }

    private String excerpt(String content, int offset, int maxChars) {
        int start = Math.max(0, offset - maxChars / 2);
        int end = Math.min(content.length(), start + maxChars);
        return content.substring(start, end);
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private Map<String, Object> failure(Exception error) {
        log.warn("SSH 远程文件只读操作失败: exceptionType={}",
                error.getClass().getName());
        return Map.of("success", false, "error", REMOTE_FILE_FAILURE_MESSAGE);
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListRemoteFilesRequest {
        @JsonProperty(value = "path", defaultValue = ".")
        @JsonPropertyDescription("远程目录路径，默认当前目录")
        private String path = ".";

        @JsonProperty(value = "limit", defaultValue = "100")
        @JsonPropertyDescription("最多返回条目数，范围 1 到 200")
        private Integer limit = DEFAULT_LIST_LIMIT;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReadRemoteTextRequest {
        @JsonProperty(required = true, value = "path")
        @JsonPropertyDescription("远程文本文件路径，不得是凭据、私钥或环境秘密文件")
        private String path;

        @JsonProperty(value = "offset", defaultValue = "0")
        @JsonPropertyDescription("字符偏移量，默认 0")
        private Integer offset = 0;

        @JsonProperty(value = "maxChars", defaultValue = "20000")
        @JsonPropertyDescription("本次最多返回字符数，范围 100 到 50000")
        private Integer maxChars = DEFAULT_READ_CHARS;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StatRemotePathRequest {
        @JsonProperty(required = true, value = "path")
        @JsonPropertyDescription("要检查的远程文件或目录路径")
        private String path;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CompareRemoteTextsRequest {
        @JsonProperty(required = true, value = "leftPath")
        @JsonPropertyDescription("左侧远程文本文件路径")
        private String leftPath;

        @JsonProperty(required = true, value = "rightPath")
        @JsonPropertyDescription("右侧远程文本文件路径")
        private String rightPath;

        @JsonProperty(value = "excerptChars", defaultValue = "500")
        @JsonPropertyDescription("差异摘要字符数，范围 100 到 2000")
        private Integer excerptChars = 500;
    }
}
