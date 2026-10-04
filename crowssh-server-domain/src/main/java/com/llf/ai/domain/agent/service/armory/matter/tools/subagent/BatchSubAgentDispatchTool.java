package com.llf.ai.domain.agent.service.armory.matter.tools.subagent;
import com.llf.ai.domain.agent.service.armory.matter.tools.ssh.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.database.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.subagent.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.execution.*;
import com.llf.ai.domain.agent.service.armory.matter.tools.adapter.*;

import com.llf.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.llf.ai.domain.agent.model.valobj.dynamic.DynamicTask;
import com.llf.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.llf.ai.domain.agent.service.event.AgentEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 批量子 Agent 派发工具 - 主 Agent 一次函数调用即可并发派发多个子 Agent 任务。
 * <p>
 * 与 {@link DynamicPlanDispatchTool}（先由独立规划器生成计划）不同，
 * 本工具由主 Agent 在函数调用参数中直接给出任务列表（含依赖关系），
 * 经校验后交给编排器按 DAG 并发执行，用于主 Agent 自主拆解的批量诊断场景。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时传入允许派发的 Agent 白名单。
 */
@Slf4j
public class BatchSubAgentDispatchTool extends BaseTool {

    /** 当前父节点声明的子 Agent 名称白名单 */
    private final List<String> allowedAgents;
    private final String agentId;
    private final String parentAgentName;

    /** DAG 编排器，负责任务并发调度 */
    private final DynamicAgentOrchestrator orchestrator;

    /** 负责发布派发工具的调用/响应事件，供 SSE 可视化 */
    private final SubAgentDispatchEventPublisher eventPublisher;

    /** 计划解析/校验器：解析函数调用参数并校验依赖合法性 */
    private final PlanValidator planValidator = new PlanValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 兼容旧调用方式；不注入事件发布器时派发仍可正常执行，只是不产生嵌套事件。
     */
    public BatchSubAgentDispatchTool(List<String> allowedAgents, DynamicAgentOrchestrator orchestrator) {
        this("", "", allowedAgents, orchestrator, null);
    }

    /** 兼容当前 AgentNode 的装配方式；事件路由器由后续 Spring 装配阶段提供。 */
    public BatchSubAgentDispatchTool(String agentId, String parentAgentName,
                                     List<String> allowedAgents,
                                     DynamicAgentOrchestrator orchestrator) {
        this(agentId, parentAgentName, allowedAgents, orchestrator, null);
    }

    /**
     * 创建批量子 Agent 派发工具。
     *
     * @param allowedAgents       允许派发的子 Agent 名称白名单
     * @param orchestrator        任务 DAG 编排器
     * @param agentEventPublisher 可选事件发布器；为空时不回流工具事件
     */
    public BatchSubAgentDispatchTool(String agentId, String parentAgentName, List<String> allowedAgents,
                                     DynamicAgentOrchestrator orchestrator,
                                     AgentEventPublisher agentEventPublisher) {
        super("dispatchSubAgents", "批量派发多个已配置子Agent，支持任务依赖、并行执行和结果汇总");
        this.agentId = agentId;
        this.parentAgentName = parentAgentName;
        this.allowedAgents = List.copyOf(allowedAgents);
        this.orchestrator = orchestrator;
        this.eventPublisher = new SubAgentDispatchEventPublisher(agentEventPublisher);
    }

    /**
     * 声明工具的函数签名：
     * tasks 为任务数组（每项含 agentName、request，可选 taskId/dependsOn/timeoutSeconds/maxRetries），
     * maxConcurrency 为可选并发上限，failFast 为可选的失败即中止开关。
     * <p>
     * 必须使用 parametersJsonSchema：google-adk-spring-ai 1.2.0 的 ToolConverter 转换 genai Schema
     * 时会丢弃数组的 items，模型看不到任务字段契约，只能猜测字段名并被下方白名单拒绝。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        Map<String, Object> taskProperties = new LinkedHashMap<>();
        taskProperties.put("agentName", Map.of("type", "string", "enum", allowedAgents,
                "description", "要派发的子 Agent 名称，只能取枚举值"));
        taskProperties.put("request", Map.of("type", "string", "description", "交给子 Agent 的完整任务说明"));
        taskProperties.put("taskId", Map.of("type", "string", "description", "可选，任务唯一标识，供 dependsOn 引用"));
        taskProperties.put("dependsOn", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 10,
                "description", "可选，前置任务的 taskId 列表"));
        taskProperties.put("timeoutSeconds", Map.of("type", "integer", "minimum", 1, "maximum", 120,
                "description", "可选，单任务超时秒数"));
        taskProperties.put("maxRetries", Map.of("type", "integer", "minimum", 0, "maximum", 0,
                "description", "可选，只能为 0，失败不自动重试"));
        Map<String, Object> taskSchema = new LinkedHashMap<>();
        taskSchema.put("type", "object");
        taskSchema.put("properties", taskProperties);
        taskSchema.put("required", List.of("agentName", "request"));
        taskSchema.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("tasks", Map.of("type", "array", "items", taskSchema, "minItems", 1, "maxItems", 10));
        properties.put("maxConcurrency", Map.of("type", "integer", "minimum", 1, "maximum", 4));
        properties.put("failFast", Map.of("type", "boolean"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("tasks"));
        parameters.put("additionalProperties", false);

        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description() + "。参数格式：{\"tasks\":[{\"agentName\":\"<"
                        + String.join("|", allowedAgents) + ">\",\"request\":\"任务说明\","
                        + "\"taskId\"?:\"t1\",\"dependsOn\"?:[\"t0\"],\"timeoutSeconds\"?:1-120}],"
                        + "\"maxConcurrency\"?:1-4,\"failFast\"?:false}，任务项不得包含其他字段")
                .parametersJsonSchema(parameters)
                .build());
    }

    /**
     * 批量派发流程：解析参数 → 补全 taskId → 构建计划并校验（上限 10）→
     * 白名单校验 → 捕获服务端绑定的完整执行上下文→
     * 交给编排器按 DAG 执行 → 返回 {success, tasks, allSucceeded}。
     * 任何环节失败都返回 {success:false, error:...}，不向上抛异常。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        // 先发布工具调用，再执行计划，确保 UI 能展示“正在派发”状态。
        eventPublisher.publishCall(toolContext, name(), args);
        long startedAt = System.currentTimeMillis();
        try {
            AgentExecutionContext context = SubAgentExecutionScope.capture(agentId, parentAgentName);
            if (args == null || !Set.of("tasks", "maxConcurrency", "failFast").containsAll(args.keySet()))
                throw new IllegalArgumentException("派发参数包含不支持的字段");
            int maxConcurrency = integer(args.getOrDefault("maxConcurrency", 4), 1, 4);
            if (args.containsKey("failFast") && !(args.get("failFast") instanceof Boolean))
                throw new IllegalArgumentException("failFast 必须是布尔值");
            // 解析函数调用参数中的任务列表
            List<Map<String, Object>> rawTasks = objectMapper.convertValue(args.get("tasks"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (rawTasks == null || rawTasks.isEmpty() || rawTasks.size() > 10) {
                return Single.just(reject(args, startedAt, "tasks 必须包含 1 到 10 个任务"));
            }

            // 转为 DynamicTask 并补全缺失的 taskId
            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> {
                        if (item == null || !Set.of("taskId", "agentName", "request", "dependsOn", "timeoutSeconds", "maxRetries").containsAll(item.keySet()))
                            throw new IllegalArgumentException("任务包含不支持的字段");
                        if (!(item.get("request") instanceof String) || !(item.get("agentName") instanceof String))
                            throw new IllegalArgumentException("任务名称和请求必须是字符串");
                        if (item.containsKey("taskId") && !(item.get("taskId") instanceof String))
                            throw new IllegalArgumentException("taskId 必须是字符串");
                        if (item.containsKey("dependsOn") && (!(item.get("dependsOn") instanceof List<?> dependencies)
                                || dependencies.size() > 10 || dependencies.stream().anyMatch(d -> !(d instanceof String))))
                            throw new IllegalArgumentException("dependsOn 必须是任务 ID 列表");
                        if (item.containsKey("timeoutSeconds")) integer(item.get("timeoutSeconds"), 1, 120);
                        if (item.containsKey("maxRetries")) integer(item.get("maxRetries"), 0, 0);
                        return objectMapper.convertValue(item, DynamicTask.class);
                    })
                    .peek(task -> {
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                    })
                    .toList();

            // 构建计划（maxConcurrency 默认 4）并校验结构/依赖合法性，任务数上限 10
            DynamicTaskPlan plan =
                    DynamicTaskPlan.builder()
                    .tasks(tasks)
                    .maxConcurrency(maxConcurrency)
                    .failFast(Boolean.TRUE.equals(args.get("failFast")))
                    .build();
            planValidator.validate(plan, 10);

            // Agent 白名单校验：任务中不允许出现未装配的 Agent
            List<String> requestedAgents = tasks.stream().map(DynamicTask::getAgentName).toList();
            if (!new HashSet<>(allowedAgents).containsAll(requestedAgents)) {
                return Single.just(reject(args, startedAt, "agentName 只能是: " + String.join(", ", allowedAgents)));
            }

            // 到这开始执行任务计划
            Map<String, Object> result = orchestrator.execute(context, plan);
            result.put("success", result.get("allSucceeded"));

            return Single.just(result);
        } catch (Exception exception) {
            return Single.just(reject(args, startedAt, String.valueOf(exception.getMessage())));
        }

    }

    /**
     * 参数校验失败时，记录模型实际提交的字段名（不含内容），并向父会话发布失败事件，
     * 避免派发被静默拒绝后客户端执行步骤里完全看不到。
     */
    private Map<String, Object> reject(Map<String, Object> args, long startedAt, String error) {
        log.warn("批量子Agent派发拒绝: error={}, argKeys={}, taskKeys={}", error, keysOf(args), taskKeysOf(args));
        String sessionId = SshExecuteAdkTool.currentAgentSessionId();
        ToolExecutionObserverRegistry.publish(sessionId, ToolExecutionEvent.completed(
                "dispatch-rejected-" + UUID.randomUUID(), name(), Map.of(),
                Map.of("success", false, "error", error), "error",
                startedAt, System.currentTimeMillis(), 0, "子 Agent 派发被拒绝：" + error));
        return ImmutableMap.of("success", false, "error", error);
    }

    private static Set<String> keysOf(Object value) {
        return value instanceof Map<?, ?> map
                ? map.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                : Set.of();
    }

    private static List<Set<String>> taskKeysOf(Map<String, Object> args) {
        if (args == null || !(args.get("tasks") instanceof List<?> tasks)) return List.of();
        return tasks.stream().limit(10).map(BatchSubAgentDispatchTool::keysOf).toList();
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max
                || number.doubleValue() != Math.rint(number.doubleValue()))
            throw new IllegalArgumentException("整数参数超出范围: " + min + ".." + max);
        return number.intValue();
    }

}
