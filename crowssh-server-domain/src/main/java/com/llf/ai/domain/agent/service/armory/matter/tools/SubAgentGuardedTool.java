package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;

/** 子工具入口检查作用域；工具失败不能被模型的成功文本覆盖。 */
public final class SubAgentGuardedTool extends BaseTool {
    private final BaseTool delegate;
    public SubAgentGuardedTool(BaseTool delegate) {
        super(delegate.name(), delegate.description());
        this.delegate = delegate;
    }
    @Override public Optional<FunctionDeclaration> declaration() { return delegate.declaration(); }
    @Override public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        return Single.defer(() -> {
            var scope = SubAgentExecutionScope.require();
            return delegate.runAsync(args, context).doOnSuccess(result -> {
                if ("denied".equals(result.get("status")) || "cancelled".equals(result.get("status"))) scope.cancelPlan();
                if (Boolean.FALSE.equals(result.get("success")) || "error".equals(result.get("status")) || "failed".equals(result.get("status"))
                        || "denied".equals(result.get("status")) || "cancelled".equals(result.get("status")))
                    scope.markToolFailed();
            }).doOnError(error -> scope.markToolFailed());
        });
    }
}
