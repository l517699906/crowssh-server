package com.llf.ai.cases.react.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.llf.ai.api.dto.ChatRequestDTO;
import com.llf.ai.api.dto.ReActResultDTO;
import com.llf.ai.cases.react.AbstractAIAgentReActSupport;
import com.llf.ai.cases.react.factory.DefaultReActFactory;
import com.llf.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具执行节点
 *
 * <p>职责：
 * <br/>1. 从上下文中获取 AI 返回的工具调用列表（由 AiCallNode 设置）
 * <br/>2. 检查工具是否已被 ADK 自动执行（FunctionResponse 已存在）
 * <br/>3. 如果未执行，则手动执行工具
 * <br/>4. 将工具结果追加到消息历史
 * <br/>5. 发送 tool_call / tool_result SSE 事件
 * <br/>6. 路由：回到 AiCallNode 继续对话 或 到 LoopDecisionNode 完成
 *
 * <p>工具执行模式：
 * <ul>
 *   <li>ADK 自动执行模式：ADK runner.runAsync() 内部自动执行工具，
 *       ToolCallNode 仅处理已有结果并路由</li>
 *   <li>手动执行模式（未来扩展）：ToolCallNode 直接调用
 *       SshExecuteAdkTool.executeCommand() 等工具方法执行</li>
 * </ul>
 *
 * <p>ReAct 循环链路：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用模型，解析 FunctionCalls）
 *         └→ ToolCallNode（处理工具结果）
 *               └→ [有工具结果] 回到 AiCallNode 继续对话
 *               └→ [无工具调用] LoopDecisionNode
 *                     └→ UserFeedbackNode
 * </pre>
 *
 * @author llf
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = dynamicContext.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - 无工具调用，跳过");
            return router(requestParameter, dynamicContext);
        }

        log.info("ReAct ToolCallNode - 处理 {} 个工具调用，已有 {} 个结果",
                toolCalls.size(), toolResults != null ? toolResults.size() : 0);

        // 检查是否已有 ADK 自动执行的结果（FunctionResponse 已返回）
        boolean adkAutoExecuted = toolResults != null && !toolResults.isEmpty();

        // 这种场景就是用户的配置的 LLM 质量不高的时候，一种鲁棒设计，尽量的增强可靠性
        if (adkAutoExecuted) {
            // ─── ADK 自动执行模式 ───
            log.info("工具已被 ADK 自动执行，处理已有结果");
            handleAdkToolResults(dynamicContext, toolCalls, toolResults);
        } else {
            // ─── 手动执行模式 ───
            log.info("工具未执行，手动执行");
            handleManualToolExecution(dynamicContext, toolCalls);
        }

        // 路由
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();

        // 有工具调用（已执行完成）→ 回到 AiCallNode 继续对话
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 检查是否达到最大步数
            if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
                log.info("达到最大步数 {}，路由到 LoopDecisionNode", dynamicContext.getMaxSteps());
                dynamicContext.setStopReason("max_steps");
                return getBean("reactLoopDecisionNode");
            }

            log.info("工具调用处理完成，回到 AiCallNode 继续对话");
            return getBean("reactAiCallNode");
        }

        // 无工具调用 → 路由到 LoopDecisionNode
        log.info("无工具调用，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADK 自动执行模式（基本上吧，不太会出问题，都能执行。除非模型很差的）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 处理 ADK 自动执行的工具结果
     * <p>ADK runner.runAsync() 内部已执行工具，FunctionResponse 已在事件流中返回
     */
    private void handleAdkToolResults(DefaultReActFactory.DynamicContext dynamicContext,
                                      List<Map<String, Object>> toolCalls,
                                      List<Map<String, Object>> toolResults) {

        // ADK runner 内部已管理对话历史（自动执行工具 + 追加 FunctionResponse）
        // 这里只做日志记录，不重复追加消息到 history
        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> result : toolResults) {
            String id = (String) result.get("id");
            if (id != null) {
                resultMap.put(id, result);
            }
        }

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");

            Map<String, Object> matchedResult = resultMap.get(toolCallId);
            if (matchedResult != null) {
                String content = (String) matchedResult.get("content");
                log.info("ADK 工具结果: id={}, name={}, result_length={}",
                        toolCallId, toolName, content != null ? content.length() : 0);
            } else {
                log.warn("未找到工具结果: id={}, name={}", toolCallId, toolName);
            }
        }

        // 清除本轮工具调用标记，避免重复路由
        // 注意：toolResults 保留，供 UserFeedbackNode 构建最终结果
        dynamicContext.getCurrentToolCalls().clear();
    }

    // ═══════════════════════════════════════════════════════════════
    //  手动执行模式（未来扩展）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 手动执行工具调用
     * <p>当 ADK 未自动执行工具时，由 ToolCallNode 直接执行
     * <p>适用于：自定义工具、MCP 工具、需要预处理/后处理的场景
     */
    private void handleManualToolExecution(DefaultReActFactory.DynamicContext dynamicContext,
                                           List<Map<String, Object>> toolCalls) throws Exception {

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            if (toolCallId == null || toolName == null) {
                log.warn("工具调用信息不完整: {}", toolCall);
                continue;
            }

            // 发送 tool_call executing 事件
            sendToolCallEvent(dynamicContext, toolCallId, toolName, "executing");

            // 执行工具
            String resultContent;
            String status = "success";
            try {
                resultContent = executeTool(toolName, argsStr);
                log.info("工具执行成功: name={}, result_length={}", toolName, resultContent.length());
            } catch (Exception e) {
                log.error("工具执行失败: name={}", toolName, e);
                resultContent = "Error executing tool '" + toolName + "': " + e.getMessage();
                status = "error";
            }

            // 截断过长结果
            resultContent = truncateToolResponse(resultContent, 4000);

            // 存储工具结果到上下文
            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("id", toolCallId);
            toolResult.put("name", toolName);
            toolResult.put("content", resultContent);
            toolResult.put("status", status);
            dynamicContext.getCurrentToolResults().add(toolResult);

            // 追加 tool 消息到消息历史（供下一轮 AI 调用使用）
            dynamicContext.appendToolMessage(toolCallId, resultContent);

            // 发送 tool_result SSE 事件
            sendToolResultEvent(dynamicContext, toolCallId, resultContent, status);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具执行
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据工具名称和参数执行对应的工具
     */
    private String executeTool(String toolName, String argsStr) throws Exception {
        log.info("手动执行工具: name={}, args={}", toolName, argsStr);

        switch (toolName) {
            case "executeCommand":
            case "execute_command":
            case "run_command":
                return executeSshTool(argsStr);
            default:
                log.warn("未知工具: {}", toolName);
                return "Unknown tool: " + toolName + ". Available tools: executeCommand";
        }
    }

    /**
     * 执行 SSH 工具
     * <p>调用 SshExecuteAdkTool.executeCommand() 执行 SSH 命令
     */
    private String executeSshTool(String argsStr) throws Exception {
        // 1. 解析参数
        String command = parseToolArg(argsStr, "command");
        if (command == null || command.isBlank()) {
            return "Error: missing 'command' argument";
        }

        // 2. 执行 SSH 命令
        Map<String, Object> result = sshExecuteAdkTool.executeCommand(command);

        // 3. 格式化结果
        return formatSshResult(result);
    }

    /**
     * 格式化 SSH 执行结果
     */
    private String formatSshResult(Map<String, Object> result) {
        if (result == null) {
            return "No result";
        }

        StringBuilder sb = new StringBuilder();

        Object output = result.get("output");
        if (output != null && !output.toString().isEmpty()) {
            sb.append(output);
        }

        Object error = result.get("error");
        if (error != null && !error.toString().isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[ERROR] ").append(error);
        }

        Object exitCode = result.get("exitCode");
        if (exitCode != null) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[Exit code: ").append(exitCode).append("]");
        }

        return !sb.isEmpty() ? sb.toString() : "Command executed with no output";
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 JSON 参数字符串中解析指定 key 的值
     */
    private String parseToolArg(String argsStr, String key) {
        if (argsStr == null || argsStr.isBlank()) return null;

        try {
            Map<String, Object> args = objectMapper.readValue(argsStr,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Object value = args.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", e.getMessage());
            // 兜底：简单字符串匹配
            String pattern = "\"" + key + "\"";
            int idx = argsStr.indexOf(pattern);
            if (idx >= 0) {
                int colonIdx = argsStr.indexOf(":", idx + pattern.length());
                if (colonIdx >= 0) {
                    String remaining = argsStr.substring(colonIdx + 1).trim();
                    if (remaining.startsWith("\"")) {
                        int endQuote = remaining.indexOf("\"", 1);
                        if (endQuote > 0) {
                            return remaining.substring(1, endQuote);
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * 截断过长的工具响应
     */
    private String truncateToolResponse(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "\n... (truncated, total " + content.length() + " chars)";
    }
}
