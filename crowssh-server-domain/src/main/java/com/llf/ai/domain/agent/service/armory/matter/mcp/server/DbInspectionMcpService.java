package com.llf.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.llf.ai.domain.agent.service.armory.matter.tools.AgentExecutionBinding;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionEvent;
import com.llf.ai.domain.agent.service.armory.matter.tools.ToolExecutionObserverRegistry;
import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionOutcome;
import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;
import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import com.llf.ai.domain.db.service.metadata.DbMetadataService;
import lombok.Data;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** 数据库结构与诊断专用工具；模型参数不包含任何资源身份。 */
@Service
public class DbInspectionMcpService {
    private final DbMetadataService metadata;
    public DbInspectionMcpService(DbMetadataService metadata) { this.metadata = metadata; }

    @Tool(description = "列出当前绑定数据库账号可见的业务数据库；默认省略系统库，结果有界")
    public Map<String, Object> listDatabases() {
        return run("listDatabases", binding -> request(DbInspectionRequest.Operation.DATABASES, null, null, 50));
    }

    @Tool(description = "列出指定数据库的表、视图、引擎和容量估算；database 省略时使用本轮绑定库，不读取表数据或注释")
    public Map<String, Object> listTables(DatabaseRequest input) {
        return run("listTables", binding -> request(DbInspectionRequest.Operation.TABLES,
                database(input == null ? null : input.getDatabase(), binding), null, 50));
    }

    @Tool(description = "读取指定表的安全字段结构与索引成员信息；不返回注释、默认值或原始建表 SQL")
    public Map<String, Object> describeTable(TableRequest input) {
        return run("describeTable", binding -> request(DbInspectionRequest.Operation.COLUMNS,
                database(input == null ? null : input.getDatabase(), binding), input == null ? null : input.getTable(), 50));
    }

    @Tool(description = "解释一条已获列授权的 SELECT，输出访问方式、索引和估算行数；不执行 EXPLAIN ANALYZE，不返回计划条件字面量")
    public Map<String, Object> explainQuery(ExplainRequest input) {
        return run("explainQuery", binding -> new DbInspectionRequest(DbInspectionRequest.Operation.EXPLAIN,
                binding.targetDatabase(), null, 50, input == null ? null : input.getSql()));
    }

    @Tool(description = "读取版本、运行时间、连接数、运行线程、慢查询与 buffer pool 白名单指标；累计值不能当作采样区间变化")
    public Map<String, Object> inspectInstance() {
        return run("inspectInstance", binding -> request(DbInspectionRequest.Operation.INSTANCE, null, null, 50));
    }

    @Tool(description = "读取当前账号可见进程的 ID、库、状态和持续时间；最多 100 行，AI 结果另受 50 行预算，不包含 SQL 原文")
    public Map<String, Object> inspectProcessList(ProcessRequest input) {
        return run("inspectProcessList", binding -> {
            int limit = input == null || input.getLimit() == null ? 20 : input.getLimit();
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("进程行数必须在 1 到 100 之间");
            return request(DbInspectionRequest.Operation.PROCESSES, null, null, limit);
        });
    }

    @Tool(description = "读取 MySQL 数据锁等待的阻塞关系和线程、事务 ID；空结果表示采样时未发现等待，不包含 SQL 或锁数据")
    public Map<String, Object> inspectLocks() {
        return run("inspectLocks", binding -> request(DbInspectionRequest.Operation.LOCKS, null, null, 50));
    }

    private Map<String, Object> run(String tool, Function<DbResourceBinding, DbInspectionRequest> buildRequest) {
        DbResourceBinding binding = AgentExecutionBinding.requireDb();
        String callId = "call_" + UUID.randomUUID();
        long started = System.currentTimeMillis();
        try {
            var request = buildRequest.apply(binding);
            var execution = metadata.inspectAi(binding, request, running -> ToolExecutionObserverRegistry.publish(
                    binding.agentSessionId(), ToolExecutionEvent.running(callId, tool, arguments(running), started)));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resourceKind", "DB_METADATA"); result.put("executionId", execution.getExecutionId());
            result.put("turnId", binding.turnId()); result.put("targetDatabase", execution.getTargetDatabase());
            result.put("state", execution.getState()); result.put("outcome", execution.getOutcome());
            result.put("success", execution.getOutcome() == DbExecutionOutcome.SUCCEEDED);
            result.put("sampledAt", java.time.Instant.now().toString());
            result.put("resultAvailable", execution.isResultAvailable()); result.put("result", execution.getResult());
            if (request.operation() == DbInspectionRequest.Operation.INSTANCE) {
                result.put("metricSemantics", "Uptime 为秒；Connections/Slow_queries/Questions/读计数为启动后累计，其余为采样瞬时值；未计算命中率");
            }
            ToolExecutionObserverRegistry.publish(binding.agentSessionId(), ToolExecutionEvent.completed(callId, tool,
                    arguments(execution), result, Boolean.TRUE.equals(result.get("success")) ? "success" : "error",
                    started, System.currentTimeMillis(), 0, null));
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            // 参数错误允许模型修正；失效轮次由领域入口持续拒绝，不能换工具恢复权限。
            return Map.of("success", false, "resourceKind", "DB_METADATA", "turnId", binding.turnId(),
                    "reason", "诊断未执行：请检查参数、查询授权及当前数据库会话状态");
        }
    }

    private static Map<String, Object> arguments(DbExecutionEntity execution) {
        return Map.of("resourceKind", "DB_METADATA", "executionId", execution.getExecutionId(),
                "turnId", execution.getTurnId(), "sqlHash", execution.getSqlHash());
    }

    private static DbInspectionRequest request(DbInspectionRequest.Operation operation, String database, String table, int limit) {
        return new DbInspectionRequest(operation, database, table, limit, null);
    }
    private static String database(String requested, DbResourceBinding binding) {
        return requested == null || requested.isBlank() ? binding.targetDatabase() : requested;
    }

    @Data
    public static class DatabaseRequest {
        @JsonPropertyDescription("可选数据库名；省略使用本轮目标库")
        private String database;
    }
    @Data
    public static class TableRequest {
        @JsonPropertyDescription("可选数据库名；省略使用本轮目标库")
        private String database;
        @JsonProperty(required = true)
        private String table;
    }
    @Data
    public static class ExplainRequest {
        @JsonProperty(required = true)
        @JsonPropertyDescription("单条 SELECT 原文，所有引用列必须已授权")
        private String sql;
    }
    @Data
    public static class ProcessRequest {
        @JsonPropertyDescription("可选，默认20，范围1到100")
        private Integer limit;
    }
}
