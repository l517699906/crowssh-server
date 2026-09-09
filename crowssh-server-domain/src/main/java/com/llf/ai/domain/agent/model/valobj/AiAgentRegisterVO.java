package com.llf.ai.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Ai Agent 智能体注册值对象
 * <p>
 * Phase 3 新增 {@link #openAiApi} 与 {@link #chatModelName}：把 Agent 装配链路中
 * 构建的 OpenAiApi 和模型名透传出来，使意图识别等旁路能力能复用 Agent 自己的模型配置，
 * 而无需为意图识别单独配置一套模型。
 * <pre>
 *   RunnerNode 装配 ─→ AiAgentRegisterVO{ openAiApi, chatModelName }
 *                          ↓ AiCallNode 读取
 *                      intentService.configure(openAiApi, chatModelName)
 *                          ↓
 *                      LLMIntentClassifier 复用构建独立 ChatModel(temp=0.1)
 * </pre>
 * @author llf
 * @date 2026/05/30
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * 智能体名称
     */
    private String appName;

    /**
     * 智能体ID
     */
    private String agentId;

    /**
     * 智能体名称
     */
    private String agentName;

    /**
     * 智能体描述
     */
    private String agentDesc;
    @Builder.Default
    private String resourceKind = "SSH";

    /**
     * 智能体执行对象
     */
    private Runner runner;

    /**
     * 智能体的 LLM API（与 Runner 共用同一套配置，
     * 供意图识别等旁路能力构建独立 ChatModel，避免单独配置模型）
     */
    private OpenAiApi openAiApi;

    /**
     * 智能体配置的模型名称（供意图识别复用）
     */
    private String chatModelName;
}
