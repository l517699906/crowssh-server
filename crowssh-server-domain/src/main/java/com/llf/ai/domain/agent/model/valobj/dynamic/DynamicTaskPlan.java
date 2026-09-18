package com.llf.ai.domain.agent.model.valobj.dynamic;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 动态任务计划 - 一次多 Agent 派发的完整执行计划。
 * <p>
 * 由规划器输出 JSON 经 PlanParser 解析、PlanValidator 校验后构建，
 * 交给 DynamicAgentOrchestrator 按 DAG 依赖关系并发执行。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTaskPlan {

    /** 计划包含的任务列表，构成一个有向无环图（DAG），不允许出现依赖环 */
    private List<DynamicTask> tasks;
    /** 最大并发度，范围 1～4，由调度器限制提交中的任务数量 */
    @Builder.Default
    private Integer maxConcurrency = 4;
    /** 兼容旧计划字段；true 会被拒绝，计划不能代替工具审批 */
    @Builder.Default
    private Boolean requireConfirmation = false;
    /**
     * 失败即中止策略：默认 false，某个任务失败后其余无依赖任务继续执行，
     * 仅其下游任务被跳过；置 true 后，首个任务失败即不再调度新的 PENDING 任务
     * （进行中的任务会跑完），剩余任务全部置为 SKIPPED。
     */
    @Builder.Default
    private Boolean failFast = false;

}
