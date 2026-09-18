package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 单个派发只是批量入口的受限快捷方式，复用相同执行和取消语义。 */
public class SubAgentDispatchTool extends BaseTool {
    private final BatchSubAgentDispatchTool batch;
    public SubAgentDispatchTool(String name, String description, BatchSubAgentDispatchTool batch) {
        super(name, description);
        this.batch = batch;
    }
    @Override public Optional<FunctionDeclaration> declaration() {
        return Optional.of(FunctionDeclaration.builder().name(name()).description(description())
                .parameters(Schema.builder().type("OBJECT")
                        .properties(Map.of("request", Schema.builder().type("STRING").build()))
                        .required(List.of("request")).build()).build());
    }
    @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        Object request = args == null ? null : args.get("request");
        if (!(request instanceof String text) || text.isBlank())
            return Single.just(Map.of("success", false, "error", "request 不能为空"));
        return batch.runAsync(Map.of("tasks", List.of(Map.of("agentName", name(), "request", text)),
                "maxConcurrency", 1), context);
    }
}
