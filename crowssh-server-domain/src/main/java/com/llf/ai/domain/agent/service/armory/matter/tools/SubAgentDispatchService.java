package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.llf.ai.domain.agent.model.valobj.dynamic.*;
import com.llf.ai.domain.agent.service.armory.catalog.AgentCatalog;
import com.llf.ai.domain.agent.service.armory.matter.session.ManagedRunnerFactory;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 所有派发入口共用的单任务执行器；不自动重放具有外部副作用的 Agent。 */
@Service
public class SubAgentDispatchService {
    private final AgentCatalog catalog;
    private final ManagedRunnerFactory runnerFactory;
    public SubAgentDispatchService(AgentCatalog catalog, ManagedRunnerFactory runnerFactory) {
        this.catalog = catalog;
        this.runnerFactory = runnerFactory;
    }

    public boolean isReadOnly(AgentExecutionContext context, DynamicTask task) {
        return catalog.isReadOnly(context.getAgentId(), context.getParentAgentName(), task.getAgentName());
    }

    public void validateAgent(AgentExecutionContext context, DynamicTask task) {
        if (catalog.find(context.getAgentId(), context.getParentAgentName(), task.getAgentName()).isEmpty())
            throw new IllegalArgumentException("子 Agent 不在当前父节点的派发目录中");
    }

    /** 仅在专用线程池中调用；调度器持有 Future，负责总截止时间与中断。 */
    public String execute(AgentExecutionContext context, DynamicTask task, String request,
                          AtomicBoolean cancelled) throws Exception {
        validateAgent(context, task);
        try (var scope = SubAgentExecutionScope.open(context, cancelled)) {
            SubAgentExecutionScope.require();
            var agent = catalog.find(context.getAgentId(), context.getParentAgentName(), task.getAgentName()).orElseThrow();
            Runner runner = runnerFactory.create(agent, agent.name(), List.of());
            String childId = task.getChildSessionId();
            runner.sessionService().createSession(agent.name(), context.getUserId(), Map.of(
                    SshExecuteAdkTool.OWNER_ID_STATE_KEY, context.getUserId(),
                    SshExecuteAdkTool.CONNECTION_ID_STATE_KEY, context.getConnectionId(),
                    SshExecuteAdkTool.TERMINAL_SESSION_ID_STATE_KEY, context.getTerminalSessionId()), childId).blockingGet();
            try {
                String[] result = {""};
                runner.runAsync(context.getUserId(), childId, Content.fromParts(Part.fromText(request)),
                                RunConfig.builder().maxLlmCalls(12).build())
                        .blockingForEach(event -> {
                            SubAgentExecutionScope.require();
                            if (event.finalResponse()) {
                                String text = event.content().map(Content::text).orElse("");
                                if (!text.isBlank()) result[0] = bounded(text);
                            }
                        });
                SubAgentExecutionScope.require();
                if (scope.toolFailed()) throw new IllegalStateException("子任务工具执行失败，不能判定业务成功");
                if (result[0].isBlank()) throw new IllegalStateException("子 Agent 未返回有效最终结果");
                return result[0];
            } finally {
                // 独立子会话不会进入用户聊天列表，结束后释放内存及上下文。
                runner.sessionService().deleteSession(agent.name(), context.getUserId(), childId).blockingAwait();
            }
        }
    }

    static String bounded(String text) {
        return text.length() <= 8000 ? text : text.substring(0, 8000) + "\n[结果已截断]";
    }
}
