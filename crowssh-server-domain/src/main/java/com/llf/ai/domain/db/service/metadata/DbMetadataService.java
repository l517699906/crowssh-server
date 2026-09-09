package com.llf.ai.domain.db.service.metadata;

import com.llf.ai.domain.db.adapter.port.IDbMetadataPort;
import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.entity.DbSchemaEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionOutcome;
import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;
import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import com.llf.ai.domain.db.service.IDbMetadataService;
import com.llf.ai.domain.db.service.session.DbSessionService;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.function.Consumer;

@Service
public class DbMetadataService implements IDbMetadataService {
    private final DbSessionService sessionService;
    private final IDbMetadataPort metadataPort;

    public DbMetadataService(DbSessionService sessionService, IDbMetadataPort metadataPort) {
        this.sessionService = sessionService;
        this.metadataPort = metadataPort;
    }

    /** 重探测不复用上次权限结果；每项登记执行身份，但只返回能力状态。 */
    @Override
    public java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> refreshCapabilities(String ownerId, String dbSessionId) {
        var states = new java.util.LinkedHashMap<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState>();
        for (var operation : List.of(DbInspectionRequest.Operation.INSTANCE,
                DbInspectionRequest.Operation.PROCESSES, DbInspectionRequest.Operation.LOCKS)) {
            var request = new DbInspectionRequest(operation, null, null, 1, null);
            var execution = sessionService.executeMetadata(ownerId, dbSessionId, null, request, context ->
                    metadataPort.inspect(context.handleId(), context.execution().getExecutionId(), request,
                            1, Math.min(2, context.timeoutSeconds())));
            var result = execution.getResult();
            if (result == null || !result.isSessionReusable() || result.getCapabilityState() == null) {
                throw new IllegalStateException("数据库能力探测未完成，执行状态：" + execution.getOutcome());
            }
            states.put(operation.name(), result.getCapabilityState());
        }
        return java.util.Map.copyOf(states);
    }

    /** AI 工具只传结构参数，资源与来源取自可信绑定。 */
    public DbExecutionEntity inspectAi(DbResourceBinding binding, DbInspectionRequest request,
                                       Consumer<DbExecutionEntity> started) {
        return sessionService.executeMetadata(binding.ownerId(), binding.dbSessionId(), binding, request, execution -> {
            started.accept(execution.execution());
            return metadataPort.inspect(execution.handleId(), execution.execution().getExecutionId(), request,
                    execution.maxRows(), execution.timeoutSeconds());
        });
    }

    @Override
    public List<String> listDatabases(String ownerId, String dbSessionId) {
        var result = inspectConsole(ownerId, dbSessionId,
                new DbInspectionRequest(DbInspectionRequest.Operation.DATABASES, null, null, 1000, null));
        return result.getRows().stream().map(row -> value(row, 0)).toList();
    }

    @Override
    public List<DbSchemaEntity> listTables(String ownerId, String dbSessionId, String database) {
        var result = inspectConsole(ownerId, dbSessionId,
                new DbInspectionRequest(DbInspectionRequest.Operation.TABLES, database, null, 1000, null));
        return result.getRows().stream().map(row -> DbSchemaEntity.builder().database(database)
                .name(value(row, 0)).kind(value(row, 1)).engine(value(row, 2))
                .estimatedRows(number(row, 3)).sizeBytes(number(row, 4)).build()).toList();
    }

    @Override
    public DbSchemaEntity describeTable(String ownerId, String dbSessionId, String database, String table) {
        var result = inspectConsole(ownerId, dbSessionId,
                new DbInspectionRequest(DbInspectionRequest.Operation.COLUMNS, database, table, 1000, null));
        var byOrdinal = new java.util.LinkedHashMap<Integer, DbSchemaEntity.Column>();
        for (var row : result.getRows()) {
            int ordinal = number(row, 0) == null ? 0 : Math.toIntExact(number(row, 0));
            byOrdinal.putIfAbsent(ordinal, DbSchemaEntity.Column.builder().ordinal(ordinal)
                    .name(value(row, 1)).typeName(value(row, 2)).nullable("YES".equals(value(row, 3)))
                    .keyType(value(row, 4)).indexName(value(row, 5)).build());
            String indexName = value(row, 5);
            if (indexName != null) {
                Long position = number(row, 6);
                Long nonUnique = number(row, 7);
                if (position == null || position < 1 || nonUnique == null) {
                    throw new IllegalStateException("数据库索引元数据不完整");
                }
                var member = new DbSchemaEntity.IndexMember(indexName, Math.toIntExact(position), nonUnique == 0, value(row, 8));
                var indexes = byOrdinal.get(ordinal).getIndexes();
                if (!indexes.contains(member)) indexes.add(member);
            }
        }
        var columns = List.copyOf(byOrdinal.values());
        return DbSchemaEntity.builder().database(database).name(table).kind("TABLE").columns(columns).build();
    }

    private DbQueryResultEntity inspectConsole(String owner, String session, DbInspectionRequest request) {
        var execution = sessionService.executeMetadata(owner, session, null, request, context ->
                metadataPort.inspect(context.handleId(), context.execution().getExecutionId(), request,
                        context.maxRows(), context.timeoutSeconds()));
        if (execution.getOutcome() != DbExecutionOutcome.SUCCEEDED || execution.getResult() == null) {
            throw new IllegalStateException("数据库结构查询未完成，执行状态：" + execution.getOutcome());
        }
        if (execution.getResult().isTruncated()) {
            throw new IllegalStateException("数据库结构查询达到结果上限，请缩小查询范围");
        }
        return execution.getResult();
    }

    private static String value(List<String> row, int index) { return index < row.size() ? row.get(index) : null; }
    private static Long number(List<String> row, int index) {
        String value = value(row, index);
        return value == null ? null : Long.valueOf(value);
    }
}
