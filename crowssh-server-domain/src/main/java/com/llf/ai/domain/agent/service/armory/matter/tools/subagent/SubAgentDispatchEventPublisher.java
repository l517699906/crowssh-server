package com.llf.ai.domain.agent.service.armory.matter.tools.subagent;

import com.llf.ai.domain.agent.service.event.AgentEventPublisher;
import com.google.adk.events.Event;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.util.Map;

/**
 * 子 Agent 派发工具的合成事件发布器。
 * <p>
 * 由于子 Agent 使用独立 Runner 运行，原始 function call/response 不一定都会进入父事件流；
 * 这里补发标准 ADK 事件，保证前端可以完整展示派发过程。
 */
final class SubAgentDispatchEventPublisher {

    /** ADK 在上下文不可用时可能返回的占位标识，不能用于精确路由。 */
    private static final String UNKNOWN = "unknown";

    /**
     * 事件发布器为可选依赖：部分工具在单元测试或非 Spring 装配场景下仍可独立运行。
     */
    private final AgentEventPublisher agentEventPublisher;

    /**
     * 创建派发工具专用的合成事件发布器。
     *
     * @param agentEventPublisher 可选；为空时所有发布操作静默跳过
     */
    SubAgentDispatchEventPublisher(AgentEventPublisher agentEventPublisher) {
        this.agentEventPublisher = agentEventPublisher;
    }

    /**
     * 发布子 Agent 派发工具的 function call 事件。
     */
    void publishCall(ToolContext toolContext, String toolName, Map<String, Object> args) {
        publish(toolContext, toolName, Part.fromFunctionCall(toolName, args));
    }

    /**
     * 发布子 Agent 派发工具的 function response 事件。
     */
    void publishResponse(ToolContext toolContext, String toolName, Map<String, Object> result) {
        publish(toolContext, toolName, Part.fromFunctionResponse(toolName, result));
    }

    /**
     * 为子 Agent 派发工具生成标准 ADK function call/response 事件。
     * <p>
     * 优先按父 invocation 路由；如果 ToolContext 不可用，则由发布器按唯一活跃会话兜底，避免并发会话串流。
     */
    private void publish(ToolContext toolContext, String toolName, Part part) {
        if (agentEventPublisher == null) {
            return;
        }

        String invocationId = toolContext == null ? null : toolContext.invocationId();
        String sessionId = toolContext == null ? null : toolContext.sessionId();
        String agentName = toolContext == null ? null : toolContext.agentName();
        String author = agentName == null || agentName.isBlank() || UNKNOWN.equals(agentName)
                ? toolName
                : agentName;

        Event event = Event.builder()
                .id(Event.generateEventId())
                .invocationId(invocationId)
                .author(author)
                .content(Content.fromParts(part))
                .build();

        if (invocationId != null && !invocationId.isBlank() && !UNKNOWN.equals(invocationId)) {
            agentEventPublisher.publish(invocationId, sessionId, event, true);
        } else {
            agentEventPublisher.publishToOnlyActiveSession(event, true);
        }
    }
}
