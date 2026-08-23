package com.llf.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * SSH 运维场景意图类型枚举
 *
 * <p>意图类型分三类语义：
 * <ul>
 *   <li><b>业务意图</b>（DIAGNOSE/CONFIGURE/DEPLOY/MONITOR/SECURITY/BACKUP/EXECUTE/EXPLAIN/SEARCH）：
 *       对应一类可被工具链执行的运维动作，可命中规则关键词或 LLM 分类。</li>
 *   <li><b>控制意图</b>（COMPOUND/CONTINUE）：COMPOUND 表示一条消息跨多个业务意图，
 *       交主模型拆解；CONTINUE 表示继续上一个多步任务，依赖 {@link TaskStateVO}。</li>
 *   <li><b>兜底意图</b>（CHAT/UNKNOWN）：放弃分类、全交主模型自行决策，不做硬路由。</li>
 * </ul>
 *
 * <p>流转约定（由 {@code IntentService} + {@code AiCallNode} 实现）：
 * <pre>
 *   规则/LLM 识别  ──→  业务意图(conf≥0.5)  ──→  注入意图标签 + ReAct 执行
 *                  ──→  COMPOUND            ──→  候选意图交主模型拆解
 *                  ──→  CONTINUE            ──→  续接 TaskStateVO 当前步骤
 *                  ──→  UNKNOWN / conf<0.5  ──→  全交主模型，不硬路由
 * </pre>
 *
 * <p>案例：
 * <pre>
 *   "nginx 502 了帮我看看"      → DIAGNOSE  (业务意图，规则命中 502/挂)
 *   "看下 502 是不是改配置导致"  → COMPOUND  (跨 DIAGNOSE+CONFIGURE)
 *   "继续"                      → CONTINUE  (续接进行中的任务态)
 *   "今天天气怎么样"            → CHAT      (闲聊，不进 ReAct)
 *   模型无法判断                → UNKNOWN   (conf=0，全交主模型)
 * </pre>
 *
 * @author llf
 */
@Getter
@AllArgsConstructor
public enum IntentTypeEnumVO {

    DIAGNOSE("诊断问题"),
    CONFIGURE("配置修改"),
    DEPLOY("部署操作"),
    MONITOR("监控查看"),
    SECURITY("安全相关"),
    BACKUP("备份恢复"),
    EXECUTE("直接执行"),
    COMPOUND("复合指令"),
    EXPLAIN("解释说明"),
    SEARCH("搜索查找"),
    CHAT("闲聊"),
    CONTINUE("继续"),
    UNKNOWN("未知");

    private final String label;
}
