package com.llf.ai.cases.react;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llf.ai.api.dto.ReActEventDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ReAct 流式事件发布器，统一维护 SSE 协议元数据和序号。
 */
@Component
public class ReActStreamEventPublisher {

    private static final int SCHEMA_VERSION = 2;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendStatus(
            DefaultReActFactory.DynamicContext context,
            String status,
            String content
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("status");
        event.setStatus(status);
        event.setContent(content);
        send(context, event);
    }

    public void sendText(
            DefaultReActFactory.DynamicContext context,
            String content,
            String fullText
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("text");
        event.setContent(content);
        event.setFullText(fullText);
        send(context, event);
    }

    public void sendToolCall(
            DefaultReActFactory.DynamicContext context,
            String toolCallId,
            String toolName,
            String status
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("tool_call");
        event.setToolCallId(toolCallId);
        event.setToolName(toolName);
        event.setStatus(status);
        send(context, event);
    }

    public void sendToolResult(
            DefaultReActFactory.DynamicContext context,
            String toolCallId,
            String content,
            String status
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("tool_result");
        event.setToolCallId(toolCallId);
        event.setContent(content);
        event.setStatus(status);
        send(context, event);
    }

    public void sendExecutionEvent(
            DefaultReActFactory.DynamicContext context,
            ToolExecutionEvent executionEvent
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("running".equals(executionEvent.getStatus()) ? "tool_call" : "tool_result");
        event.setToolCallId(executionEvent.getToolCallId());
        event.setToolName(executionEvent.getToolName());
        event.setCommand(executionEvent.getCommand());
        event.setStatus(executionEvent.getStatus());
        event.setStartedAt(executionEvent.getStartedAt());

        if ("tool_result".equals(event.getEvent())) {
            event.setCompletedAt(executionEvent.getCompletedAt());
            event.setDurationMs(executionEvent.getDurationMs());
            event.setOutputLength(executionEvent.getOutputLength());
            event.setContent(executionEventContent(executionEvent));
            event.setErrorMessage(executionEvent.getErrorMessage());
        }

        send(context, event);
    }

    private String executionEventContent(ToolExecutionEvent event) {
        if ("success".equals(event.getStatus())) {
            return "executeCommand".equals(event.getToolName())
                    ? "命令执行完成，完整输出已保留在终端中。"
                    : "工具执行完成。";
        }
        return event.getErrorMessage() == null || event.getErrorMessage().isBlank()
                ? "工具执行失败。"
                : event.getErrorMessage();
    }

    public void sendRoundEnd(
            DefaultReActFactory.DynamicContext context,
            int currentStep,
            int maxSteps,
            boolean shouldContinue,
            int totalToolCalls
    ) throws IOException {
        ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
        stepInfo.setCurrentStep(currentStep);
        stepInfo.setMaxSteps(maxSteps);
        stepInfo.setShouldContinue(shouldContinue);
        stepInfo.setTotalToolCalls(totalToolCalls);

        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("round_end");
        event.setStepInfo(stepInfo);
        send(context, event);
    }

    public void sendDone(
            DefaultReActFactory.DynamicContext context,
            ReActResultDTO result
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("done");
        event.setContent(result.getContent());
        event.setStopReason(result.getStopReason());
        send(context, event);
    }

    public void sendError(
            DefaultReActFactory.DynamicContext context,
            String content
    ) throws IOException {
        ReActEventDTO event = new ReActEventDTO();
        event.setEvent("error");
        event.setContent(content);
        event.setCode("AGENT_STREAM_ERROR");
        event.setRetryable(false);
        send(context, event);
    }

    public void sendHeartbeat(DefaultReActFactory.DynamicContext context) throws IOException {
        if (!canSend(context)) {
            return;
        }
        synchronized (context) {
            context.getEmitter().send(": heartbeat\n\n");
        }
    }

    private void send(
            DefaultReActFactory.DynamicContext context,
            ReActEventDTO event
    ) throws IOException {
        if (!canSend(context)) {
            return;
        }
        synchronized (context) {
            long sequence = context.nextEventSequence();
            event.setSchemaVersion(SCHEMA_VERSION);
            event.setEventId(context.getSessionId() + ":" + sequence);
            event.setSequence(sequence);
            event.setTimestamp(System.currentTimeMillis());
            event.setSessionId(context.getSessionId());
            context.getEmitter().send(objectMapper.writeValueAsString(event) + "\n");
        }
    }

    private boolean canSend(DefaultReActFactory.DynamicContext context) {
        return context != null
                && context.isStreaming()
                && context.getEmitter() != null
                && context.getStreamActive() != null
                && context.getStreamActive().get();
    }
}
