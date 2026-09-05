package com.llf.ai.domain.agent.service.intent.classifier.impl;

import com.llf.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.llf.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.llf.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.llf.ai.domain.agent.service.intent.IntentService;
import com.llf.ai.domain.agent.service.intent.classifier.IIntentClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 意图分类器（第2层，100~500ms）
 * <p>
 * 当规则分类器置信度不足时，调用独立 LLM（temperature=0.1）进行意图分类。
 * <p>
 * 复用智能体装配链路中 Agent 自己的 API 配置
 * （{@link OpenAiApi} + 模型名），由 {@link IntentService} 在分类前通过
 * {@link #configure(OpenAiApi, String)} 注入。构建的 ChatModel 独立于 Agent
 * 的主 ChatModel：无工具回调、temperature=0.1，保证意图识别的确定性与隔离性。
 *
 * <p>调用与解析流程：
 * <pre>
 *   IntentService.classify(conf 不足)
 *        ↓
 *   LLMIntentClassifier.classify
 *        ├─ 组装上下文（最近意图 + 进行中任务态）
 *        ├─ 渲染 CLASSIFY_PROMPT_TEMPLATE（含意图清单 + few-shot 示例）
 *        ├─ chatModel.call(prompt)  → 原始 JSON 文本
 *        └─ parseResponse → IntentResultVO
 *              ├─ 提取首个 {...} JSON
 *              ├─ 解析 intent/confidence/entities/candidates
 *              └─ 解析失败 → UNKNOWN(conf=0)
 * </pre>
 *
 * <p>隔离性说明：此 ChatModel 不挂任何 ToolCallback，纯文本往返，避免意图识别
 * 意外触发工具执行；temperature=0.1 保证同一输入多次分类结果稳定。
 *
 * <p>案例：输入 "服务器好像有点慢，帮我瞧瞧"
 * <pre>
 *   规则层命中关键词"慢"不足 → < 0.8 下沉
 *   LLM 返回 {"intent":"DIAGNOSE","confidence":0.7,"entities":{},"candidates":["MONITOR"]}
 *   → IntentResultVO{ intent=DIAGNOSE, conf=0.7, candidates=[MONITOR] }
 * </pre>
 *
 * @author llf
 */
@Slf4j
@Component
public class LLMIntentClassifier implements IIntentClassifier {

    // 独立 ChatModel 实例（无工具回调、temperature=0.1），由 configure 注入后惰性构建
    private volatile ChatModel chatModel;
    // Agent 装配链路传入的 OpenAiApi（与 Runner 共用一套配置）
    private volatile OpenAiApi openAiApi;
    // Agent 配置的模型名称（如 "gpt-4"、"claude-3"）
    private volatile String modelName;

    // JSON 解析器，用于解析 LLM 返回的 JSON 响应
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 由 IntentService 在分类前注入 Agent 的 API 配置。
     * 仅在配置变化时重建 ChatModel，避免每次分类都构建。
     * <p>
     * 案例：
     * <pre>
     *   第1次调用 configure(api, "gpt-4")
     *   -> 创建 ChatModel，model="gpt-4", temperature=0.1
     *
     *   第2次调用 configure(api, "gpt-4")  // 配置相同
     *   -> 不重建，复用已有 ChatModel
     *
     *   第3次调用 configure(api, "claude-3")  // 模型变化
     *   -> 重建 ChatModel，model="claude-3", temperature=0.1
     * </pre>
     *
     * @param openAiApi  Agent 装配链路构建的 OpenAiApi
     * @param modelName  Agent 配置的模型名
     */
    public synchronized void configure(OpenAiApi openAiApi, String modelName) {
        /**
         * 由 IntentService 在分类前注入 Agent 的 API 配置。
         * 仅在配置变化时重建 ChatModel，避免每次分类都构建。
         * <p>
         * <b>线程安全</b>：使用 synchronized 确保多线程环境下 ChatModel 的单例性。
         * <p>
         * 案例：
         * <pre>
         *   第1次调用 configure(api, "gpt-4")
         *   -> 创建 ChatModel，model="gpt-4", temperature=0.1
         *
         *   第2次调用 configure(api, "gpt-4")  // 配置相同
         *   -> 不重建，复用已有 ChatModel
         *
         *   第3次调用 configure(api, "claude-3")  // 模型变化
         *   -> 重建 ChatModel，model="claude-3", temperature=0.1
         * </pre>
         *
         * @param openAiApi Agent 装配链路构建的 OpenAiApi
         * @param modelName Agent 配置的模型名
         */
        if (openAiApi == null || modelName == null || modelName.isBlank()) {
            return;
        }
        // 配置未变则不重建
        if (openAiApi.equals(this.openAiApi) && modelName.equals(this.modelName) && this.chatModel != null) {
            return;
        }
        this.openAiApi = openAiApi;
        this.modelName = modelName;
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.1) // 意图识别需要更确定的结果
                        .build())
                .build();
    }

    /**
     * LLM 意图分类 Prompt 模板：包含意图清单 + few-shot 示例 + 对话上下文占位符。
     * <p>
     * 占位符：{{CONTEXT}} → 最近意图历史 + 进行中任务态；{{MESSAGE}} → 待分类的用户输入。
     * 输出约定：仅返回 JSON，格式 {"intent":"类型","confidence":0.0-1.0,"entities":{},"candidates":["次选"]}
     */
    private static final String CLASSIFY_PROMPT_TEMPLATE = """
            你是一个 SSH 运维场景的意图识别系统。分析用户输入，返回 JSON 格式的意图分类结果。
            
            ## 意图类型
            - DIAGNOSE: 诊断问题（服务挂了、报错、异常排查）
            - CONFIGURE: 配置修改（改配置文件、调参数）
            - DEPLOY: 部署操作（部署、发布、回滚）
            - MONITOR: 监控查看（看日志、查状态、看资源使用）
            - SECURITY: 安全相关（防火墙、权限、证书）
            - BACKUP: 备份恢复（备份数据、恢复数据）
            - EXECUTE: 直接执行（帮我跑某命令）
            - COMPOUND: 复合指令（同时包含多个意图，如"看下502是不是因为改了配置"）
            - EXPLAIN: 解释说明（这个命令什么意思）
            - SEARCH: 搜索查找（找文件、查进程）
            - CHAT: 闲聊
            - CONTINUE: 继续上一个任务
            - UNKNOWN: 无法判断，无法判断时请返回 UNKNOWN 并将 confidence 设为 0
            
            ## 输出格式（仅返回 JSON，无其他内容）
            {"intent":"类型","confidence":0.0-1.0,"entities":{"key":"value"},"candidates":["次选意图1","次选意图2"]}
            当输入明显跨多个意图时 intent 返回 COMPOUND，candidates 列出涉及的具体意图（按可能性排序）。
            
            ## 示例
            输入: "nginx 502了，帮我看看"
            输出: {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"},"candidates":["MONITOR"]}
            
            输入: "帮我改下 redis 的 maxmemory 配置"
            输出: {"intent":"CONFIGURE","confidence":0.9,"entities":{"service":"redis","config":"maxmemory"},"candidates":[]}
            
            输入: "看下服务器磁盘使用情况"
            输出: {"intent":"MONITOR","confidence":0.9,"entities":{"resource":"disk"},"candidates":[]}
            
            输入: "这个命令 awk '{print $1}' access.log 是什么意思"
            输出: {"intent":"EXPLAIN","confidence":0.95,"entities":{"command":"awk"},"candidates":[]}
            
            输入: "看下 nginx 502 是不是因为我刚改了 redis 配置导致连接池打满"
            输出: {"intent":"COMPOUND","confidence":0.85,"entities":{"service":"nginx","error":"502"},"candidates":["DIAGNOSE","MONITOR","CONFIGURE"]}
            
            ## 对话上下文（最近意图历史）
            {{CONTEXT}}

            ## 待分类的用户输入
            "{{MESSAGE}}"

            输出（仅返回 JSON）:
            """;

    /**
     * 使用 LLM 进行意图分类，解析原始响应为 IntentResultVO。
     * <p>
     * 流程：
     * <pre>
     *   1. 组装上下文：最近意图历史 + 任务态描述
     *   2. 渲染 Prompt：注入上下文 + 用户消息
     *   3. 调用 LLM：获取 JSON 响应
     *   4. 解析响应：提取 intent/confidence/entities/candidates
     *   5. 失败降级：解析失败 → UNKNOWN(conf=0)
     * </pre>
     * <p>
     * 案例 1：正常分类
     * <pre>
     *   message = "nginx 502了，帮我看看"
     *
     *   LLM 返回：
     *   {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"},"candidates":["MONITOR"]}
     *
     *   解析结果：
     *   IntentResultVO {
     *     intent=DIAGNOSE,
     *     confidence=0.95,
     *     entities={service=nginx, error=502},
     *     candidates=[MONITOR]
     *   }
     * </pre>
     * <p>
     * 案例 2：复合意图
     * <pre>
     *   message = "看下 nginx 502 是不是因为我刚改了 redis 配置导致连接池打满"
     *
     *   LLM 返回：
     *   {"intent":"COMPOUND","confidence":0.85,"entities":{"service":"nginx","error":"502"},"candidates":["DIAGNOSE","MONITOR","CONFIGURE"]}
     *
     *   解析结果：
     *   IntentResultVO {
     *     intent=COMPOUND,
     *     confidence=0.85,
     *     candidates=[DIAGNOSE, MONITOR, CONFIGURE]
     *   }
     * </pre>
     * <p>
     * 案例 3：解析失败（降级 UNKNOWN）
     * <pre>
     *   LLM 返回乱码或无效 JSON：
     *   "I think this is a monitoring task..."
     *
     *   解析失败 → 返回：
     *   IntentResultVO { intent=UNKNOWN, confidence=0.0 }
     * </pre>
     *
     * @param message 用户原始消息
     * @param context 会话上下文（最近意图历史、任务态），可为 null
     * @return 意图识别结果，解析失败时返回 UNKNOWN(conf=0)
     */
    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        // 1) 组装上下文描述：最近意图历史 + 进行中任务态，让 LLM 具备连贯性
        String recentIntents = "";
        if (context != null && context.getRecentIntents() != null) {
            recentIntents = context.getRecentIntents().stream()
                    .map(h -> h.getIntent().name())
                    .collect(Collectors.joining(", "));
        }
        String taskDesc = "无";
        if (context != null && context.getTaskState() != null
                && !context.getTaskState().isCompleted()
                && context.getTaskState().getRootIntent() != null) {
            taskDesc = "进行中任务: " + context.getTaskState().getRootIntent()
                    + ", 当前步骤: " + context.getTaskState().getCurrentStepIndex();
        }

        String contextDesc = (recentIntents.isEmpty() ? "" : "最近意图: " + recentIntents)
                + (taskDesc.equals("无") ? "" : (recentIntents.isEmpty() ? "" : " | ") + taskDesc);
        if (contextDesc.isEmpty()) {
            contextDesc = "无";
        }
        String prompt = CLASSIFY_PROMPT_TEMPLATE
                .replace("{{CONTEXT}}", contextDesc)
                .replace("{{MESSAGE}}", message);

        // 2) 调用 LLM：chatModel 未注入或调用异常都降级为 UNKNOWN，保证不阻塞主流程
        if (chatModel == null) {
            // Agent 尚未装配完成或未注入配置，无法走 LLM 分类
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
        try {
            String response = chatModel.call(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
    }

    /**
     * 从 LLM 原始文本中提取首个 JSON 对象并解析；任一环节失败均降级 UNKNOWN，
     * 含解释性文字或咒语等非 JSON 输出不会导致分类器抛异常。
     * <p>
     * 解析步骤：
     * <ol>
     *   <li>正则提取首个 {...} JSON 片段</li>
     *   <li>解析为 Map，提取 intent/confidence/entities/candidates</li>
     *   <li>将 candidates 列表转换为 IntentTypeEnumVO 列表（过滤无效值）</li>
     *   <li>任何异常 → 返回 UNKNOWN(conf=0)</li>
     * </ol>
     *
     * @param response LLM 返回的原始文本
     * @return 解析后的 IntentResultVO，失败时返回 UNKNOWN
     */
    @SuppressWarnings("unchecked")
    private IntentResultVO parseResponse(String response) {
        try {
            // 提取 JSON 部分
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            IntentTypeEnumVO intent = IntentTypeEnumVO.valueOf(
                    String.valueOf(parsed.get("intent")).toUpperCase());
            double confidence = parsed.containsKey("confidence")
                    ? Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;
            Map<String, String> entities = parsed.containsKey("entities")
                    ? (Map<String, String>) parsed.get("entities") : Map.of();

            List<IntentTypeEnumVO> candidates = List.of();
            if (parsed.containsKey("candidates")) {
                Object cands = parsed.get("candidates");
                if (cands instanceof List<?> list) {
                    candidates = list.stream()
                            .filter(o -> o instanceof String)
                            .map(o -> {
                                try {
                                    return IntentTypeEnumVO.valueOf(((String) o).toUpperCase());
                                } catch (IllegalArgumentException ex) {
                                    return null;
                                }
                            })
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .collect(Collectors.toList());
                }
            }

            return IntentResultVO.builder()
                    .intent(intent).confidence(confidence)
                    .entities(entities).rawResponse(response)
                    .candidateIntents(candidates).build();
        } catch (Exception e) {
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0)
                    .entities(Map.of()).rawResponse(response).build();
        }
    }
}
