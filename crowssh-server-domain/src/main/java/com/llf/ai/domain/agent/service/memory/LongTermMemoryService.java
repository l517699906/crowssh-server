package com.llf.ai.domain.agent.service.memory;

import com.llf.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import com.llf.ai.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.llf.ai.domain.agent.model.entity.ChatMessageEntity;
import com.llf.ai.domain.agent.model.entity.LongTermMemoryEntity;
import com.llf.ai.domain.agent.service.ILongTermMemoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 长期记忆服务实现——本节核心组件
 * <p>
 * 自动从对话流中提取 4 种结构化记忆，并支持关键词召回（粗筛+精排两阶段策略）。
 * <p>
 * <b>四种记忆类型：</b>
 * <table border="1">
 * <tr><th>类型</th><th>触发条件</th><th>key 策略</th><th>置信度</th></tr>
 * <tr><td>USER_PREFERENCE</td><td>用户消息含偏好词（"以后""默认""记住"等）</td><td>sha1("pref:"+内容)</td><td>0.85</td></tr>
 * <tr><td>ENVIRONMENT_FACT</td><td>工具输出匹配 OS 关键词</td><td>"os:ubuntu"</td><td>0.70</td></tr>
 * <tr><td>SOFTWARE_FACT</td><td>工具输出匹配软件版本模式</td><td>"redis:version"</td><td>0.78~0.82</td></tr>
 * <tr><td>TROUBLESHOOTING_CASE</td><td>工具失败 / 助手含"结论""原因"等</td><td>sha1("failure:"/"assistant:"+内容)</td><td>0.68~0.72</td></tr>
 * </table>
 * <p>
 * <b>召回策略：粗筛+精排</b>
 * <ul>
 *   <li>粗筛：从 DB 拉最近 120 条记忆</li>
 *   <li>精排：关键词重叠打分（重叠数×2 + USER_PREFERENCE 额外+1），按分数降序取 Top-N</li>
 * </ul>
 * <p>
 * 设计原则：零外部依赖（不需要 Embedding API、不需要向量数据库），纯 MySQL + 关键词匹配。
 * 对于 SSH 运维场景，关键词（nginx、redis、502、timeout）天然具有高区分度，召回精度够用。
 */
@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** 记忆类型：用户偏好（最高价值，用户自己说的，置信度 0.85） */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";

    /** 记忆类型：环境事实（从工具输出检测 OS，置信度 0.70） */
    private static final String TYPE_ENVIRONMENT_FACT = "ENVIRONMENT_FACT";

    /** 记忆类型：软件版本（从工具输出检测版本号，置信度 0.78~0.82） */
    private static final String TYPE_SOFTWARE_FACT = "SOFTWARE_FACT";

    /** 记忆类型：排查案例（失败信号/助手结论，置信度 0.68~0.72） */
    private static final String TYPE_TROUBLESHOOTING_CASE = "TROUBLESHOOTING_CASE";

    /** 用户偏好检测关键词列表：消息中包含任一词即判定为可能的偏好表达 */
    private static final List<String> PREFERENCE_HINTS = List.of("以后", "默认", "记住", "优先", "不要", "请用", "习惯", "统一使用");

    /** 通用软件版本正则：匹配 "nginx version: 1.24.0"、"mysql Ver 8.0.42" 等格式 */
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?i)\\b(nginx|redis|mysql|postgres(?:ql)?|docker|java)\\b"
                    + "[^\\n]{0,80}?(?:version|ver|版本)\\b\\s*[:=]?\\s*"
                    + "(?:[\\w.\\-]+/)?[\\\"']?([\\w.\\-]+)");

    /** Redis 专用版本正则：匹配 "redis_version:7.0.11" 格式（通用正则可能匹配不到） */
    private static final Pattern REDIS_VERSION_PATTERN = Pattern.compile("(?i)redis_version:([\\w.\\-]+)");

    /** 操作系统检测正则：匹配 ubuntu、centos、debian 等 OS 关键词 */
    private static final Pattern OS_PATTERN = Pattern.compile("(?i)(ubuntu|centos|debian|rocky|almalinux|fedora|linux|macos|darwin|windows)");

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    /** 会话历史仓储（消息落库 + 冷启动恢复） */
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    /**
     * {@inheritDoc}
     * <p>
     * 偏好检测逻辑：消息中包含 PREFERENCE_HINTS 任一关键词即判定为偏好表达。
     * memory_key 用 sha1("pref:"+内容) 确保同一偏好不重复入库——用户三次说"以后加 sudo"，
     * 三次 sha1 结果一样，走 saveOrUpdate 更新 hit_count。
     * 意图标签拼入关键词，提高后续召回命中率。
     */
    @Override
    public void recordUserMessage(String userId, String sessionId, String message, String intentLabel) {
        if (isBlank(userId) || isBlank(message)) {
            return;
        }

        String normalized = normalize(message);
        boolean looksLikePreference = PREFERENCE_HINTS.stream().anyMatch(normalized::contains);
        if (!looksLikePreference) {
            return;
        }

        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_USER_PREFERENCE)
                .memoryKey(sha1("pref:" + normalized))
                .content(truncate(message, 300))
                .keywords(joinKeywords(extractKeywords(message + " " + nullSafe(intentLabel))))
                .sourceRole("user")
                .confidence(0.85)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 从工具执行结果中用正则匹配提取四类信息：
     * <ol>
     *   <li>操作系统检测（OS_PATTERN → ENVIRONMENT_FACT）</li>
     *   <li>通用软件版本检测（VERSION_PATTERN → SOFTWARE_FACT）</li>
     *   <li>Redis 专用版本检测（REDIS_VERSION_PATTERN → SOFTWARE_FACT）</li>
     *   <li>失败信号记录（仅 success=false 且 looksLikeHighSignalFailure → TROUBLESHOOTING_CASE）</li>
     * </ol>
     * 为什么要给 Redis 单独写一条正则？因为 Redis 版本输出格式 redis_version:7.0.11
     * 和 nginx version: nginx/1.x 格式不一样，通用正则可能匹配不到。
     */
    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success) {
        if (isBlank(userId) || isBlank(resultContent)) {
            return;
        }

        String text = truncate(resultContent, 500);

        // 1. 操作系统检测
        Matcher osMatcher = OS_PATTERN.matcher(text);
        if (osMatcher.find()) {
            String os = osMatcher.group(1).toLowerCase(Locale.ROOT);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_ENVIRONMENT_FACT)
                    .memoryKey("os:" + os)
                    .content("目标环境可能为 " + os)
                    .keywords(joinKeywords(extractKeywords(os + " environment system os")))
                    .sourceRole("tool")
                    .confidence(0.70)
                    .hitCount(1)
                    .build());
        }

        // 2. 软件版本检测（通用正则）
        Matcher versionMatcher = VERSION_PATTERN.matcher(text);
        if (versionMatcher.find()) {
            String software = versionMatcher.group(1).toLowerCase(Locale.ROOT);
            String version = versionMatcher.group(2);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey(software + ":version")
                    .content(software + " 版本为 " + version)
                    .keywords(joinKeywords(extractKeywords(software + " version " + version)))
                    .sourceRole("tool")
                    .confidence(0.78)
                    .hitCount(1)
                    .build());
        }

        // 3. Redis 版本检测（专用正则，补充通用正则的遗漏）
        Matcher redisVersionMatcher = REDIS_VERSION_PATTERN.matcher(text);
        if (redisVersionMatcher.find()) {
            String version = redisVersionMatcher.group(1);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey("redis:version")
                    .content("redis 版本为 " + version)
                    .keywords(joinKeywords(extractKeywords("redis version " + version)))
                    .sourceRole("tool")
                    .confidence(0.82)
                    .hitCount(1)
                    .build());
        }

        // 4. 失败信号记录（仅工具执行失败时）
        if (!success && looksLikeHighSignalFailure(text)) {
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_TROUBLESHOOTING_CASE)
                    .memoryKey(sha1("failure:" + normalize(text)))
                    .content("历史失败信号: " + text)
                    .keywords(joinKeywords(extractKeywords(text + " " + nullSafe(toolName))))
                    .sourceRole("tool")
                    .confidence(0.68)
                    .hitCount(1)
                    .build());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 只有当助手回复包含"结论""原因""建议""修复""根因"等关键词时才提取，
     * 避免把每一条中间推理过程都存下来——大部分回复没有记忆价值。
     */
    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent) {
        if (isBlank(userId) || isBlank(assistantContent)) {
            return;
        }

        String normalized = normalize(assistantContent);
        if (!(normalized.contains("结论") || normalized.contains("原因") || normalized.contains("建议") || normalized.contains("修复") || normalized.contains("根因"))) {
            return;
        }

        String summary = truncate(assistantContent, 400);
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_TROUBLESHOOTING_CASE)
                .memoryKey(sha1("assistant:" + normalize(summary)))
                .content(summary)
                .keywords(joinKeywords(extractKeywords(summary)))
                .sourceRole("assistant")
                .confidence(0.72)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 用户消息落库（仅首轮）+ 偏好记忆提取，case 层统一入口。
     */
    @Override
    public void saveUserMessage(String userId, String sessionId, String message, String intentLabel, boolean firstRound) {
        if (!firstRound) {
            return;
        }
        if (!isBlank(sessionId) && !isBlank(message)) {
            try {
                chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .role("user")
                        .content(message)
                        .priority("MEDIUM")
                        .tokenCount(message.length() / 2)
                        .build());
            } catch (Exception e) {
                log.warn("保存用户消息落库失败 sessionId={}", sessionId, e);
            }
        }
        recordUserMessage(userId, sessionId, message, intentLabel);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 助手回复落库 + 结论记忆提取，case 层统一入口。
     */
    @Override
    public void saveAssistantMessage(String userId, String sessionId, String assistantContent) {
        if (isBlank(assistantContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("assistant")
                    .content(assistantContent)
                    .priority("MEDIUM")
                    .tokenCount(assistantContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存助手消息落库失败 sessionId={}", sessionId, e);
        }
        recordAssistantConclusion(userId, sessionId, assistantContent);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 工具结果落库（priority=HIGH）+ 环境事实/软件版本/失败信号记忆提取，case 层统一入口。
     */
    @Override
    public void saveToolMessage(String userId, String sessionId, String toolName, String toolCallId, String resultContent, boolean success) {
        if (isBlank(resultContent)) {
            return;
        }
        try {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(sessionId)
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .priority("HIGH")
                    .tokenCount(resultContent.length() / 2)
                    .build());
        } catch (Exception e) {
            log.warn("保存工具消息落库失败 sessionId={}, tool={}", sessionId, toolName, e);
        }
        recordToolObservation(userId, sessionId, toolName, resultContent, success);
    }

    @Override
    public List<ChatMessageEntity> getRecentMessages(String userId, String sessionId, int limit) {
        if (isBlank(userId) || isBlank(sessionId)) {
            return List.of();
        }
        try {
            List<ChatMessageEntity> messages = chatHistoryRepository.getRecentMessages(userId, sessionId, limit);
            return messages == null ? List.of() : messages;
        } catch (Exception e) {
            log.warn("按归属查询最近消息失败 userId={} sessionId={}", userId, sessionId, e);
            return List.of();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * 两阶段召回（从DB直接拿，会比 RAG 更准）：
     * <ol>
     *   <li>粗筛：从 DB 拉最近 120 条记忆</li>
     *   <li>精排：关键词重叠打分，过滤 score>0，按分数降序+更新时间降序取 Top-N</li>
     * </ol>
     */
    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }

        // 阶段一：粗筛——从 DB 拉最近 120 条记忆（也可以提取出来做一个可配置的）
        List<LongTermMemoryEntity> candidates;
        try {
            candidates = longTermMemoryRepository.queryRecentByUserId(userId, 120);
        } catch (RuntimeException e) {
            // 长期记忆是旁路上下文；DB 短暂不可用时不应阻断主对话。
            log.warn("查询长期记忆失败 userId={}", userId, e);
            return List.of();
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 阶段二：精排——关键词重叠打分
        Set<String> queryKeywords = extractKeywords(query);
        return candidates.stream()
                .filter(Objects::nonNull)
                .map(memory -> new MemoryScore(memory, score(memory, queryKeywords)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt(MemoryScore::score).reversed()
                        .thenComparing(ms -> ms.memory.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit > 0 ? limit : 5)
                .map(MemoryScore::memory)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * 输出格式：每行一条记忆，"- [类型] 内容"
     */
    @Override
    public String buildMemorySummary(String userId, String query, int limit) {
        List<LongTermMemoryEntity> memories = queryRelevantMemories(userId, query, limit);
        if (memories.isEmpty()) {
            return "";
        }

        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + truncate(memory.getContent(), 160))
                .collect(Collectors.joining("\n"));
    }

    // ==================== 私有工具方法 ====================

    /**
     * 保存长期记忆（try-catch 旁路写入，失败不影响主流程）
     */
    private void saveMemory(LongTermMemoryEntity memoryEntity) {
        try {
            longTermMemoryRepository.saveOrUpdate(memoryEntity);
        } catch (Exception e) {
            log.warn("保存长期记忆失败 type={}, key={}", memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(), e);
        }
    }

    /**
     * 计算记忆与查询关键词的匹配分数。
     * <p>
     * 打分公式：关键词重叠数 × 2 + USER_PREFERENCE 加权（+1）。
     * USER_PREFERENCE 有额外加分，因为用户偏好是全局适用的——无论用户问什么，
     * "以后加 sudo"这条偏好都应该被召回。
     *
     * @param memory         长期记忆
     * @param queryKeywords  查询关键词集合
     * @return 匹配分数，0 表示不匹配
     */
    private int score(LongTermMemoryEntity memory, Set<String> queryKeywords) {
        if (memory == null) {
            return 0;
        }
        if (queryKeywords.isEmpty()) {
            // 无查询关键词时，仅 USER_PREFERENCE 返回 1 分（全局适用）
            return TYPE_USER_PREFERENCE.equals(memory.getMemoryType()) ? 1 : 0;
        }

        Set<String> memoryKeywords = extractKeywords(nullSafe(memory.getKeywords()) + " " + nullSafe(memory.getContent()));
        int overlap = 0;
        for (String keyword : queryKeywords) {
            if (memoryKeywords.contains(keyword)) {
                overlap += 2;
            }
        }
        // 用户偏好记忆额外加 1 分（全局适用，应优先召回）
        if (TYPE_USER_PREFERENCE.equals(memory.getMemoryType())) {
            overlap += 1;
        }
        return overlap;
    }

    /**
     * 关键词提取：分割 → 过滤 → 去重。
     * <p>
     * <ol>
     *   <li>分割：用 [^a-z0-9_\u4e00-\u9fa5]+ 正则分割，保留英文/数字/下划线/中文</li>
     *   <li>过滤：长度 < 2 的片段丢弃，停用词丢弃</li>
     *   <li>去重：LinkedHashSet 保证唯一且保持插入顺序</li>
     * </ol>
     *
     * @param text 原始文本
     * @return 关键词集合
     */
    private Set<String> extractKeywords(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        String normalized = normalize(text);
        String[] parts = normalized.split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        Set<String> keywords = new LinkedHashSet<>();
        Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(part -> !STOP_WORDS.contains(part))
                .forEach(keywords::add);
        return keywords;
    }

    /**
     * 将关键词集合拼接为逗号分隔的字符串，存入 DB 的 keywords 字段
     */
    private String joinKeywords(Set<String> keywords) {
        return String.join(",", keywords);
    }

    /**
     * 文本归一化：转小写 + 换行替换为空格 + trim
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
    }

    /**
     * 截断文本，超长部分用 "..." 表示
     */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 判断工具输出是否包含高信号失败特征（permission denied、connection refused 等）。
     * 只有高信号失败才值得记录为排查案例，避免把无关紧要的失败都存下来。
     */
    private boolean looksLikeHighSignalFailure(String text) {
        String normalized = normalize(text);
        return normalized.contains("permission denied")
                || normalized.contains("connection refused")
                || normalized.contains("no such file")
                || normalized.contains("not found")
                || normalized.contains("failed")
                || normalized.contains("error");
    }

    /**
     * SHA-1 哈希，用于生成 memory_key 去重键。
     * 失败时降级为 hashCode，保证不抛异常。
     */
    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** 记忆打分内部记录类，用于排序 */
    private record MemoryScore(LongTermMemoryEntity memory, int score) {}

    /** 中英文停用词集合：在关键词提取时被过滤，避免无意义词干扰匹配 */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(List.of(
            "请", "帮", "一下", "这个", "那个", "现在", "需要", "进行", "继续", "问题", "情况",
            "the", "and", "for", "with", "that", "this", "from", "into", "have", "has"
    ));
}
