package com.llf.ai.domain.agent.service.armory.matter.tools;

import com.google.adk.tools.Annotations;
import com.llf.ai.domain.db.config.DbSessionGovernanceProperties;
import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionState;
import com.llf.ai.domain.db.service.session.DbApprovalRegistry;
import com.llf.ai.domain.db.service.session.DbSessionService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** AI 直接 SQL 工具；来源、资源和轮次均来自服务端绑定。 */
@Service
public final class DbQueryAdkTool {
    private final DbSessionService sessions;
    private final DbApprovalRegistry approvals;
    private final DbSessionGovernanceProperties properties;

    public DbQueryAdkTool(DbSessionService sessions, CommandApprovalService approvalService,
                          DbSessionGovernanceProperties properties) {
        this.sessions = sessions;
        this.approvals = approvalService.databaseApprovals();
        this.properties = properties;
    }

    @Annotations.Schema(name = "executeQuery", description = "在独立数据库 AI 连接执行一条 SQL；支持的修改必须先由用户审批，不能操作控制台事务")
    public Map<String, Object> executeQuery(@Annotations.Schema(name = "sql", description = "完整单条 SQL；写操作必须显式限定数据库") String sql) {
        var binding = AgentExecutionBinding.requireDb();
        sessions.requireAiTurn(binding);
        String callId = "call_" + UUID.randomUUID();
        long started = System.currentTimeMillis();
        DbExecutionEntity prepared = sessions.prepareAi(binding, sql);
        Map<String, Object> args = Map.of("sqlHash", prepared.getSqlHash(), "executionId", prepared.getExecutionId(),
                "turnId", binding.turnId(), "resourceKind", "DB_SQL");
        DbApprovalRegistry.Ticket ticket = null;
        try {
            if (prepared.getState() == DbExecutionState.WAITING_APPROVAL) {
                if (!ToolExecutionObserverRegistry.hasDbApprovalObserver(binding.agentSessionId())) {
                    sessions.stopAiTurn(binding, approvals);
                    return completed(callId, binding.agentSessionId(), args, started,
                            sessions.getExecution(binding.ownerId(), binding.dbSessionId(), prepared.getExecutionId()));
                }
                ticket = approvals.request(binding, prepared.getExecutionId(), callId, prepared.getSqlHash(),
                        properties.getApproval().getTtlSeconds());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("target", sessions.approvalTarget(binding));
                details.put("impactNotice", "影响行数执行后才能确定；触发器、级联、非事务表和 DDL 可能产生不可回滚影响");
                details.put("connectionNotice", "使用独立自动提交 AI 连接，不提交或回滚控制台事务");
                details.put("resourceKind", "DB_SQL"); details.put("resourceSnapshot", binding);
                details.put("executionId", prepared.getExecutionId()); details.put("sql", sql);
                details.put("riskReasons", prepared.getPolicyReason()); details.put("expiresAt", ticket.expiresAt().toString());
                ToolExecutionObserverRegistry.publish(binding.agentSessionId(), ToolExecutionEvent.databaseApprovalRequired(
                        callId, args, started, ticket.id(), prepared.getRiskLevel(), details));
                if (approvals.await(ticket) != DbApprovalRegistry.State.APPROVED) {
                    sessions.stopAiTurn(binding, approvals);
                    return completed(callId, binding.agentSessionId(), args, started,
                            sessions.getExecution(binding.ownerId(), binding.dbSessionId(), prepared.getExecutionId()));
                }
            }
            if (!prepared.isTerminal()) ToolExecutionObserverRegistry.publish(binding.agentSessionId(),
                    ToolExecutionEvent.running(callId, "executeQuery", args, started));
            DbExecutionEntity result = prepared.isTerminal() ? prepared
                    : sessions.executeAi(binding, prepared.getExecutionId(), approvals, ticket);
            return completed(callId, binding.agentSessionId(), args, started, result);
        } catch (InterruptedException interrupted) {
            sessions.stopAiTurn(binding, approvals);
            Thread.currentThread().interrupt();
            return completed(callId, binding.agentSessionId(), args, started,
                    sessions.getExecution(binding.ownerId(), binding.dbSessionId(), prepared.getExecutionId()));
        } catch (RuntimeException failure) {
            sessions.stopAiTurn(binding, approvals);
            return completed(callId, binding.agentSessionId(), args, started,
                    sessions.getExecution(binding.ownerId(), binding.dbSessionId(), prepared.getExecutionId()));
        } finally {
            if (ticket != null) approvals.complete(ticket);
        }
    }

    private Map<String, Object> completed(String callId, String chat, Map<String, Object> args,
                                          long started, DbExecutionEntity execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceKind", "DB_SQL"); result.put("executionId", execution.getExecutionId());
        result.put("turnId", execution.getTurnId()); result.put("targetDatabase", execution.getTargetDatabase());
        result.put("state", execution.getState().name()); result.put("outcome", execution.getOutcome());
        result.put("success", execution.getOutcome() == com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.SUCCEEDED);
        result.put("resultAvailable", execution.isResultAvailable()); result.put("result", execution.getResult());
        result.put("reason", execution.getPolicyReason());
        ToolExecutionObserverRegistry.publish(chat, ToolExecutionEvent.completed(callId, "executeQuery", args, result,
                Boolean.TRUE.equals(result.get("success")) ? "success" : "error", started,
                System.currentTimeMillis(), 0, null));
        return result;
    }
}
