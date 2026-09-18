package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.llf.ai.domain.agent.model.valobj.dynamic.AgentExecutionContext;
import com.llf.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 动态规划派发工具 - "先规划、后派发"的全自动多 Agent 编排入口。
 * <p>
 * 主 Agent 感到任务复杂时调用本工具（只传用户请求），流程：
 * PlannerAgentBuilder 让独立规划器 LLM 输出 JSON 任务计划 →
 * PlanParser 解析、PlanValidator 校验 → 编排器按 DAG 并发执行 →
 * 返回汇总结果。与 BatchSubAgentDispatchTool 的区别在于：
 * 计划由专门的规划器生成，主 Agent 无需自行拆解任务列表。
 * <p>
 * 注：本工具在装配阶段创建（非 Spring 管理），构造时注入规划器与编排器等依赖。
 */
@Slf4j
public class DynamicPlanDispatchTool extends BaseTool {

    private final PlannerAgentBuilder plannerAgentBuilder;
    private final DynamicAgentOrchestrator orchestrator;
    private final PlanParser planParser;
    private final PlanValidator planValidator;
    private final OpenAiApi openAiApi;
    private final String modelName;
    private final List<String> allowedAgents;

    public DynamicPlanDispatchTool(PlannerAgentBuilder plannerAgentBuilder,
                                   DynamicAgentOrchestrator orchestrator,
                                   PlanParser planParser,
                                   PlanValidator planValidator,
                                   OpenAiApi openAiApi,
                                   String modelName,
                                   List<String> allowedAgents) {
        super("planAndDispatchSubAgents", "根据用户任务复杂度规划并派发多个子Agent");
        this.plannerAgentBuilder = plannerAgentBuilder;
        this.orchestrator = orchestrator;
        this.planParser = planParser;
        this.planValidator = planValidator;
        this.openAiApi = openAiApi;
        this.modelName = modelName;
        this.allowedAgents = List.copyOf(allowedAgents);
    }

    /**
     * 声明工具的函数签名：入参仅一个必填字符串 request（用户原始任务描述）。
     */
    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder()
                .name(name())
                .description(description())
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of(
                                "request", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
    }

    /**
     * 规划 + 派发完整流程：规划器生成计划 JSON → 解析/校验 →
     * 构建执行上下文（透传终端会话）→ 编排器执行 → 返回 {success, tasks, allSucceeded}。
     * 任何环节失败都返回 {success:false, error:...}，不向上抛异常。
     */
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.just(Map.of("success", false,
                "error", "独立 Planner 尚未启用，请使用 dispatchSubAgents"));
    }
}
