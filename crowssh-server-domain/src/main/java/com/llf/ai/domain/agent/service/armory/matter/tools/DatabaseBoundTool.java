package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/** 在实际工具入口恢复本次调用身份，原生 FunctionTool 与 Spring AI 工具统一适用。 */
public final class DatabaseBoundTool extends BaseTool {
    private final BaseTool delegate;
    public DatabaseBoundTool(BaseTool delegate) {
        super(delegate.name(), delegate.description());
        this.delegate = delegate;
    }
    @Override public Optional<FunctionDeclaration> declaration() { return delegate.declaration(); }
    @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.defer(() -> {
            try (var ignored = AgentExecutionBinding.install(DatabaseInvocationBindings.resolve(context))) {
                return delegate.runAsync(args, context);
            }
        });
    }
}
