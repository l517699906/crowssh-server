package com.llf.ai.domain.agent.service.prompt.dynamic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 里程碑追踪器，通过可热加载的正则规则识别关键事件，并按会话维度缓存。
 *
 * @author llf
 * 2026/7/30 23:16
 */
@Slf4j
@Component
public class MilestoneTracker {

    private static final String DEFAULT_RULES_RESOURCE = "config/milestone-rules.json";
    private static final int SUPPORTED_RULES_VERSION = 1;
    private static final int MAX_RULES = 200;
    private static final int MAX_PATTERN_LENGTH = 4096;
    private static final int MAX_RULE_FILE_BYTES = 1024 * 1024;
    private static final int MAX_MILESTONES = 50;

    private final Map<String, LinkedList<MilestoneVO>> milestones = new ConcurrentHashMap<>();
    private final AtomicReference<List<CompiledRule>> activeRules = new AtomicReference<>(List.of());
    private final AtomicReference<String> activeFingerprint = new AtomicReference<>();
    private final AtomicReference<String> rejectedFingerprint = new AtomicReference<>();
    private final ObjectMapper objectMapper;
    private final Path rulesFile;
    private final byte[] defaultRulesContent;

    @Autowired
    public MilestoneTracker(
            @Value("${ai.agent.milestone.rules-file:./config/milestone-rules.json}") String rulesFile
    ) {
        this(resolveRulesFile(rulesFile), readDefaultRulesContent());
    }

    MilestoneTracker(Path rulesFile, String defaultRulesJson) {
        this(rulesFile, defaultRulesJson.getBytes(StandardCharsets.UTF_8));
    }

    private MilestoneTracker(Path rulesFile, byte[] defaultRulesContent) {
        this.rulesFile = rulesFile.toAbsolutePath().normalize();
        this.defaultRulesContent = defaultRulesContent.clone();
        this.objectMapper = new ObjectMapper();

        List<CompiledRule> defaultRules = compileRules(defaultRulesContent, "classpath:" + DEFAULT_RULES_RESOURCE);
        activeRules.set(defaultRules);
        activeFingerprint.set(fingerprint(defaultRulesContent));

        reloadRulesIfChanged();
        log.info("里程碑规则初始化完成: rules={}, externalFile={}", activeRules.get().size(), this.rulesFile);
    }

    /**
     * 检测并记录里程碑事件。规则按配置文件顺序匹配，第一个命中的规则生效。
     */
    public void detectAndRecord(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isEmpty()) {
            return;
        }

        String normalizedRole = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        for (CompiledRule rule : activeRules.get()) {
            if (!rule.role().equals(normalizedRole) || !rule.pattern().matcher(content).find()) {
                continue;
            }

            push(sessionId, MilestoneVO.builder()
                    .type(rule.type())
                    .content(truncate(content, 200))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("里程碑记录: sessionId={}, ruleId={}, type={}, content={}",
                    sessionId, rule.id(), rule.type(), truncate(content, 100));
            return;
        }
    }

    /**
     * 定期检查外部规则文件。新规则全部校验通过后才会原子替换当前快照。
     */
    @Scheduled(fixedDelayString = "${ai.agent.milestone.reload-interval-ms:2000}")
    public synchronized void reloadRulesIfChanged() {
        byte[] content;
        try {
            ensureExternalRulesFile();
            content = Files.readAllBytes(rulesFile);
            if (content.length > MAX_RULE_FILE_BYTES) {
                throw new IllegalArgumentException("规则文件不能超过 " + MAX_RULE_FILE_BYTES + " 字节");
            }
        } catch (Exception e) {
            reportRejected("io:" + e.getClass().getName() + ":" + e.getMessage(), e);
            return;
        }

        String fingerprint = fingerprint(content);
        if (Objects.equals(fingerprint, activeFingerprint.get())) {
            rejectedFingerprint.set(null);
            return;
        }
        if (Objects.equals(fingerprint, rejectedFingerprint.get())) {
            return;
        }

        try {
            List<CompiledRule> newRules = compileRules(content, rulesFile.toString());
            activeRules.set(newRules);
            activeFingerprint.set(fingerprint);
            rejectedFingerprint.set(null);
            log.info("里程碑规则热加载成功: file={}, rules={}", rulesFile, newRules.size());
        } catch (Exception e) {
            reportRejected(fingerprint, e);
        }
    }

    /**
     * 获取指定会话最近的 N 条里程碑事件。
     */
    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        LinkedList<MilestoneVO> list = milestones.get(sessionId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    /**
     * 清除指定会话的全部里程碑记录。
     */
    public void clear(String sessionId) {
        if (sessionId != null) {
            milestones.remove(sessionId);
        }
    }

    private void push(String sessionId, MilestoneVO milestoneVO) {
        LinkedList<MilestoneVO> list = milestones.computeIfAbsent(sessionId, key -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) {
                list.removeFirst();
            }
        }
    }

    private void ensureExternalRulesFile() throws IOException {
        if (Files.exists(rulesFile)) {
            return;
        }

        Path parent = rulesFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.write(rulesFile, defaultRulesContent, StandardOpenOption.CREATE_NEW);
            log.info("已生成默认里程碑规则文件: {}", rulesFile);
        } catch (FileAlreadyExistsException ignored) {
            // 其他实例已完成初始化，继续读取现有文件。
        }
    }

    private List<CompiledRule> compileRules(byte[] content, String source) {
        try {
            RulesDocument document = objectMapper.readValue(content, RulesDocument.class);
            if (document == null || !Objects.equals(document.getVersion(), SUPPORTED_RULES_VERSION)) {
                throw new IllegalArgumentException(
                        "规则版本必须为 " + SUPPORTED_RULES_VERSION + ", source=" + source
                );
            }
            if (document.getRules() == null) {
                throw new IllegalArgumentException("rules 不能为空, source=" + source);
            }
            if (document.getRules().size() > MAX_RULES) {
                throw new IllegalArgumentException("规则数量不能超过 " + MAX_RULES + ", source=" + source);
            }

            Set<String> ruleIds = new HashSet<>();
            List<CompiledRule> compiledRules = new ArrayList<>();
            for (RuleDefinition definition : document.getRules()) {
                String id = required(definition.getId(), "id", source);
                if (!ruleIds.add(id)) {
                    throw new IllegalArgumentException("规则 id 重复: " + id + ", source=" + source);
                }
                if (Boolean.FALSE.equals(definition.getEnabled())) {
                    continue;
                }

                String role = required(definition.getRole(), "role", source).toLowerCase(Locale.ROOT);
                String typeValue = required(definition.getType(), "type", source).toUpperCase(Locale.ROOT);
                String regex = required(definition.getPattern(), "pattern", source);
                if (regex.length() > MAX_PATTERN_LENGTH) {
                    throw new IllegalArgumentException("规则 " + id + " 的 pattern 过长");
                }

                MilestoneVO.Type type;
                try {
                    type = MilestoneVO.Type.valueOf(typeValue);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("规则 " + id + " 的 type 无效: " + typeValue, e);
                }

                int flags = Boolean.TRUE.equals(definition.getCaseInsensitive())
                        ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                        : 0;
                try {
                    compiledRules.add(new CompiledRule(id, role, type, Pattern.compile(regex, flags)));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("规则 " + id + " 的 pattern 无效: " + e.getDescription(), e);
                }
            }
            return List.copyOf(compiledRules);
        } catch (IOException e) {
            throw new IllegalArgumentException("规则文件解析失败: " + source, e);
        }
    }

    private void reportRejected(String failureFingerprint, Exception error) {
        String previous = rejectedFingerprint.getAndSet(failureFingerprint);
        if (!Objects.equals(previous, failureFingerprint)) {
            log.error("里程碑规则加载失败，继续使用上一版规则: file={}, reason={}",
                    rulesFile, error.getMessage());
        }
    }

    private static String required(String value, String field, String source) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("规则字段 " + field + " 不能为空, source=" + source);
        }
        return value.trim();
    }

    private static String fingerprint(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }

    private static Path resolveRulesFile(String configuredPath) {
        String path = configuredPath == null || configuredPath.isBlank()
                ? "./config/milestone-rules.json"
                : configuredPath.trim();
        return Path.of(path);
    }

    private static byte[] readDefaultRulesContent() {
        try (InputStream input = MilestoneTracker.class.getClassLoader()
                .getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("缺少默认里程碑规则: " + DEFAULT_RULES_RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取默认里程碑规则失败", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    private record CompiledRule(
            String id,
            String role,
            MilestoneVO.Type type,
            Pattern pattern
    ) {
    }

    @Data
    private static class RulesDocument {
        private Integer version;
        private List<RuleDefinition> rules;
    }

    @Data
    private static class RuleDefinition {
        private String id;
        private String role;
        private String type;
        private String pattern;
        private Boolean enabled = Boolean.TRUE;
        private Boolean caseInsensitive = Boolean.FALSE;
    }
}
