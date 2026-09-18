package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.llf.ai.domain.agent.model.valobj.dynamic.DynamicTaskPlan;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 执行前校验任务结构、DAG、并发/时长预算，拒绝模型注入运行态及自动重试。 */
@Service
public class PlanValidator {

    /**
     * 执行完整校验，校验失败抛出 {@link IllegalArgumentException}。
     * <p>
     * 如果使用的模型能力不强，可能会反馈错误（如输出空计划、幻觉出不存在的 taskId），
     * 导致本方法抛异常——这正好起到提前暴露模型质量问题、阻断脏计划进入执行层的作用。
     *
     * @param plan     待校验任务计划
     * @param maxTasks 任务数量上限（主 Agent 派发时通常为 10）
     */
    public void validate(DynamicTaskPlan plan, int maxTasks) {
        // 规则 1：计划非空 —— LLM 可能返回空计划或解析失败得到 null
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            throw new IllegalArgumentException("task plan is empty");
        }
        // 规则 1b：数量上限 —— 防止 LLM 规划出 50 个任务这种失控规模，拖垮执行资源
        if (plan.getTasks().size() > maxTasks) {
            throw new IllegalArgumentException("too many tasks: " + plan.getTasks().size());
        }

        if (plan.getMaxConcurrency() == null || plan.getMaxConcurrency() < 1 || plan.getMaxConcurrency() > 4)
            throw new IllegalArgumentException("maxConcurrency 必须在 1 到 4 之间");
        if (Boolean.TRUE.equals(plan.getRequireConfirmation()))
            throw new IllegalArgumentException("计划确认不代替现有工具审批，暂不支持 requireConfirmation");
        Set<String> taskIds = new HashSet<>();
        for (var task : plan.getTasks()) {
            if (task == null || task.getTaskId() == null || task.getTaskId().isBlank()
                    || task.getTaskId().length() > 128 || task.getAgentName() == null || task.getAgentName().isBlank()
                    || task.getRequest() == null || task.getRequest().isBlank() || task.getRequest().length() > 16000
                    || task.getDependsOn() == null || task.getDependsOn().stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("任务字段缺失或超过长度限制");
            if (!taskIds.add(task.getTaskId())) throw new IllegalArgumentException("duplicate task id: " + task.getTaskId());
            if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() < 1 || task.getTimeoutSeconds() > 120)
                throw new IllegalArgumentException("timeoutSeconds 必须在 1 到 120 之间");
            if (task.getMaxRetries() == null || task.getMaxRetries() != 0)
                throw new IllegalArgumentException("子任务不允许自动重试");
            if (task.getChildSessionId() != null || task.getStatus() != com.llf.ai.domain.agent.model.valobj.dynamic.TaskStatus.PENDING
                    || task.getAttempts() == null || task.getAttempts() != 0
                    || task.getResult() == null || !task.getResult().isEmpty()
                    || task.getError() == null || !task.getError().isEmpty())
                throw new IllegalArgumentException("运行状态只能由服务端生成");
        }

        // 规则 3：依赖必须存在 —— 引用不存在的任务 = 悬空依赖，
        // 编排器会一直等待一个永远不会完成的任务。
        for (var task : plan.getTasks()) {
            for (String dependency : task.getDependsOn()) {
                if (!taskIds.contains(dependency)) {
                    throw new IllegalArgumentException("unknown dependency: " + dependency);
                }
            }
        }

        // 规则 4：依赖不能成环 —— A→B→C→A 的循环依赖会让所有任务互相等待，
        // 编排器永远选不出"无可执行任务"之外的出口，即永久卡死。
        if (hasCycle(plan)) {
            throw new IllegalArgumentException("task dependency cycle");
        }
    }

    /** 遍历所有任务做依赖环检测（任意一个成环即返回 true）。
     *  已检测过的节点会被 visited 剪枝跳过，整体复杂度 O(V+E)。 */
    private boolean hasCycle(DynamicTaskPlan plan) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (var task : plan.getTasks()) {
            if (hasCycle(task.getTaskId(), task.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 三色标记 DFS 环检测。
     * <p>
     * 原理：visiting 表示当前递归栈中的节点（灰色，正在检测的路径），
     * visited 表示已完成检测的节点（黑色，后续可直接跳过）。
     * 判定关键：DFS 递归过程中又碰到了 visiting 中的节点，说明依赖链
     * 绕回到了"当前路径上"的自己——即找到回边（back edge），存在环。
     * <p>
     * 为什么需要两个集合？只记 visited 无法区分两种情况：
     * <ul>
     *   <li>"节点在当前路径上"（成环，需报错）</li>
     *   <li>"节点已检测过且无环"（安全，剪枝跳过）</li>
     * </ul>
     * 这就是经典的三色标记法（白/灰/黑），JVM GC 可达性分析、死锁检测用的同款算法。
     */
    private boolean hasCycle(String taskId, List<String> dependencies, DynamicTaskPlan plan,
                             Set<String> visiting, Set<String> visited) {

        if (visiting.contains(taskId)) return true;   // ⭐ 回边 = 当前递归栈中的节点再次出现 → 有环
        if (visited.contains(taskId)) return false;   // 已彻底检测过的节点无环，剪枝跳过
        visiting.add(taskId);

        // 沿 dependsOn 向依赖方向深度优先：找到被依赖的任务，递归检测它的依赖链
        for (String dependency : dependencies) {
            var parent = plan.getTasks().stream()
                    .filter(item -> item.getTaskId().equals(dependency))
                    .findFirst().orElse(null);
            if (parent != null && hasCycle(dependency, parent.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        // 当前节点的整条依赖链都检测完毕且移出递归栈，标记为已检测
        visiting.remove(taskId);
        visited.add(taskId);

        return false;
    }

}
