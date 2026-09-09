package com.llf.ai.domain.db.service.session;

import com.llf.ai.domain.db.adapter.port.DbSessionBusyException;
import com.llf.ai.domain.db.adapter.port.IDbSessionPort;
import com.llf.ai.domain.db.adapter.port.ISqlAnalysisPort;
import com.llf.ai.domain.db.adapter.repository.IDbConnectionRepository;
import com.llf.ai.domain.db.config.DbSessionGovernanceProperties;
import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.entity.DbSessionEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionSourceEnum;
import com.llf.ai.domain.db.model.valobj.DbExecutionState;
import com.llf.ai.domain.db.model.valobj.DbLaneStatusEnum;
import com.llf.ai.domain.db.model.valobj.DbSessionStatusEnum;
import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;
import com.llf.ai.domain.db.model.valobj.DbResourceBinding;
import com.llf.ai.domain.db.model.valobj.DbExecutionOutcome;
import com.llf.ai.domain.db.model.valobj.DbOperationKind;
import com.llf.ai.domain.db.model.valobj.SqlStatementKind;
import com.llf.ai.domain.db.model.valobj.SqlPolicyAction;
import com.llf.ai.domain.db.model.valobj.SqlPolicyDecision;
import com.llf.ai.domain.db.service.IDbSessionReaper;
import com.llf.ai.domain.db.service.IDbSessionService;
import com.llf.ai.domain.db.service.sql.SqlExecutionPolicy;
import com.llf.ai.domain.ssh.service.ISshConnectionOwnershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class DbSessionService implements IDbSessionService, IDbSessionReaper {
    private final IDbConnectionRepository connectionRepository;
    private final IDbSessionPort sessionPort;
    private final ISqlAnalysisPort analysisPort;
    private final DbSessionGovernanceProperties properties;
    private final SqlExecutionPolicy executionPolicy;
    private final Clock clock;
    private final ISshConnectionOwnershipService sshConnectionOwnershipService;
    private final Map<String, DbSessionEntity> sessions = new ConcurrentHashMap<>();
    private final Map<String, DbExecutionEntity> executions = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final DbExecutionResultCache resultCache;
    private java.util.concurrent.Executor consoleExecutor;
    private DbApprovalRegistry approvalRegistry;

    @Autowired
    public void setApprovalService(com.llf.ai.domain.agent.service.armory.matter.tools.CommandApprovalService service) {
        approvalRegistry = service.databaseApprovals();
    }

    /** 配置写入与执行准入共用协调边界；网络资源清理放在全局锁外。 */
    public <T> T mutateConnection(String ownerId, String connectionId, java.util.function.Supplier<T> mutation) {
        List<DbSessionEntity> invalidated = new ArrayList<>();
        T result;
        synchronized (lifecycleLock) {
            result = mutation.get();
            for (DbSessionEntity session : sessions.values()) {
                if (!Objects.equals(ownerId, session.getOwnerId()) || !Objects.equals(connectionId, session.getConnectionId())) continue;
                synchronized (session) {
                    // OPENING 仍占额度，完成建连时版本复核会拒绝发布并释放资源。
                    if (session.getLifecycleStatus() == DbSessionStatusEnum.OPENING) continue;
                    if (session.getLifecycleStatus() != DbSessionStatusEnum.READY) continue;
                    invalidateSession(session);
                    invalidated.add(session);
                }
            }
        }
        for (DbSessionEntity session : invalidated) {
            try { close(ownerId, session.getDbSessionId()); }
            catch (RuntimeException failure) {
                log.warn("配置变更后清理数据库会话失败 dbSessionId={} exceptionType={}", session.getDbSessionId(), failure.getClass().getSimpleName());
            }
        }
        return result;
    }

    @Autowired
    public void setConsoleExecutor(@org.springframework.beans.factory.annotation.Qualifier("dbConsoleExecutor")
                                   java.util.concurrent.Executor executor) {
        this.consoleExecutor = Objects.requireNonNull(executor);
    }

    @org.springframework.context.event.EventListener
    public void onSshConnectionInvalidated(com.llf.ai.domain.ssh.model.valobj.SshConnectionInvalidatedEvent event) {
        List<DbSessionEntity> invalidated = new ArrayList<>();
        synchronized (lifecycleLock) {
            for (DbSessionEntity session : sessions.values()) {
                if (!Objects.equals(event.ownerId(), session.getOwnerId())
                        || !Objects.equals(event.connectionId(), session.getTunnelSshConnectionId())) continue;
                synchronized (session) {
                    if (session.getLifecycleStatus() == DbSessionStatusEnum.OPENING) {
                        // 保留在途建连额度，完成路径看到 CLOSING 后释放新建句柄。
                        session.setLifecycleStatus(DbSessionStatusEnum.CLOSING);
                    } else if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                        invalidateSession(session);
                        invalidated.add(session);
                    }
                }
            }
        }
        for (DbSessionEntity session : invalidated) {
            try { close(session.getOwnerId(), session.getDbSessionId()); }
            catch (RuntimeException failure) {
                log.warn("SSH 失效后清理数据库会话失败 dbSessionId={} exceptionType={}",
                        session.getDbSessionId(), failure.getClass().getSimpleName());
            }
        }
    }
    private final Map<String, com.llf.ai.domain.db.model.valobj.DbResourceBinding> aiTurns = new ConcurrentHashMap<>();
    private final Map<String, AiLaneLease> aiLaneOwners = new ConcurrentHashMap<>();
    public record AiLaneLease(String leaseId, com.llf.ai.domain.db.model.valobj.DbResourceBinding binding, String handleId) { }

    /** 仅认证审批卡片使用的非凭据目标信息，必须与本轮配置版本一致。 */
    public record ApprovalTarget(String connectionName, String host, int port, String username,
                                 String tunnelSshConnectionId, long configVersion) { }

    public ApprovalTarget approvalTarget(DbResourceBinding binding) {
        DbSessionEntity session = requireSession(binding.ownerId(), binding.dbSessionId());
        synchronized (session) {
            requireAiTurn(binding);
            DbConnectionEntity connection = connectionRepository.find(binding.ownerId(), binding.dbConnectionId());
            if (connection == null || connection.getConfigVersion() != binding.configVersion()) {
                throw new IllegalStateException("数据库配置已变化，审批目标失效");
            }
            return new ApprovalTarget(connection.getConnectionName(), connection.getHost(), connection.getPort(),
                    connection.getUsername(), connection.getTunnelSshConnectionId(), connection.getConfigVersion());
        }
    }

    /** 每个工作台只允许一个活动 AI 流；资源快照由服务端当前状态生成。 */
    public com.llf.ai.domain.db.model.valobj.DbResourceBinding beginAiTurn(
            String ownerId, String dbSessionId, String agentSessionId, String turnId) {
        requireOwner(agentSessionId);
        requireOwner(turnId);
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        synchronized (lifecycleLock) {
            synchronized (session) {
                if (session.getLifecycleStatus() != DbSessionStatusEnum.READY) {
                    throw new IllegalStateException("数据库工作台尚未就绪");
                }
                DbConnectionEntity connection = connectionRepository.find(ownerId, session.getConnectionId());
                if (connection == null || connection.getConfigVersion() != session.getConfigVersion()) {
                    throw new IllegalStateException("数据库配置已变化，请重新打开工作台");
                }
                var binding = new com.llf.ai.domain.db.model.valobj.DbResourceBinding(ownerId, agentSessionId, turnId,
                        session.getConnectionId(), dbSessionId, session.getSessionGeneration(), session.getConfigVersion(),
                        session.getCurrentDatabase(), session.getTargetContextVersion());
                if (aiTurns.putIfAbsent(dbSessionId, binding) != null) {
                    throw new DbSessionBusyException("数据库工作台已有活动 AI 对话");
                }
                session.setLastActiveAt(now());
                return binding;
            }
        }
    }

    public void endAiTurn(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding) {
        DbSessionEntity session = sessions.get(binding.dbSessionId());
        if (session == null) { aiTurns.remove(binding.dbSessionId(), binding); return; }
        synchronized (session) { aiTurns.remove(binding.dbSessionId(), binding); }
    }

    public List<DbExecutionEntity> stopAiTurn(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding,
                                            DbApprovalRegistry approvals) {
        DbSessionEntity session = sessions.get(binding.dbSessionId());
        if (session == null) return List.of();
        List<String> running = new ArrayList<>();
        List<DbExecutionEntity> captured = new ArrayList<>();
        String handle;
        synchronized (session) {
            if (!Objects.equals(session.getOwnerId(), binding.ownerId())
                    || session.getSessionGeneration() != binding.sessionGeneration()) {
                throw new IllegalArgumentException("数据库工作台会话不存在");
            }
            // 与 prepare/启动共用 session 协调边界，取消先到时后续工具无法登记。
            aiTurns.remove(binding.dbSessionId(), binding);
            if (approvals != null) approvals.cancelTurn(binding.ownerId(), binding.agentSessionId(), binding.turnId());
            for (DbExecutionEntity execution : executions.values()) {
                if (execution.getSource() != DbExecutionSourceEnum.AI
                        || !Objects.equals(execution.getOwnerId(), binding.ownerId())
                        || !Objects.equals(execution.getDbSessionId(), binding.dbSessionId())
                        || !Objects.equals(execution.getAgentSessionId(), binding.agentSessionId())
                        || !Objects.equals(execution.getTurnId(), binding.turnId()) || execution.isTerminal()) continue;
                execution.setCancelRequested(true);
                if (execution.getState() == DbExecutionState.RUNNING) running.add(execution.getExecutionId());
                else finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED, null);
                captured.add(execution);
            }
            handle = session.getAiHandleId();
        }
        // 取消不持有 session monitor；由基础设施协调精确 Statement 和迟到取消。
        if (handle != null) for (String id : running) sessionPort.cancel(handle, id);
        synchronized (session) { return captured.stream().map(this::copyExecution).toList(); }
    }

    /** 只返回指定可信轮次的执行快照，包含已结束执行，供取消响应说明实际状态。 */
    public List<DbExecutionEntity> aiTurnExecutions(DbResourceBinding binding) {
        DbSessionEntity session = sessions.get(binding.dbSessionId());
        if (session == null) return List.of();
        synchronized (session) {
            if (!Objects.equals(session.getOwnerId(), binding.ownerId())
                    || session.getSessionGeneration() != binding.sessionGeneration()) {
                throw new IllegalArgumentException("数据库工作台会话不存在");
            }
            return executions.values().stream()
                    .filter(execution -> execution.getSource() == DbExecutionSourceEnum.AI
                            && Objects.equals(execution.getOwnerId(), binding.ownerId())
                            && Objects.equals(execution.getDbSessionId(), binding.dbSessionId())
                            && Objects.equals(execution.getAgentSessionId(), binding.agentSessionId())
                            && Objects.equals(execution.getTurnId(), binding.turnId()))
                    .map(this::copyExecution).toList();
        }
    }

    public void requireAiTurn(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding) {
        DbSessionEntity session = requireSession(binding.ownerId(), binding.dbSessionId());
        synchronized (session) {
            DbConnectionEntity connection = connectionRepository.find(binding.ownerId(), binding.dbConnectionId());
            if (session.getLifecycleStatus() != DbSessionStatusEnum.READY
                    || !binding.equals(aiTurns.get(binding.dbSessionId())) || connection == null
                    || connection.getConfigVersion() != binding.configVersion()
                    || session.getSessionGeneration() != binding.sessionGeneration()
                    || session.getTargetContextVersion() != binding.targetContextVersion()
                    || !Objects.equals(session.getCurrentDatabase(), binding.targetDatabase())) {
                throw new IllegalStateException("数据库 AI 轮次或目标已失效");
            }
        }
    }

    /** 内部 AI 执行入口预占独立 lane；返回不透明句柄，不向 HTTP 或模型暴露。 */
    public AiLaneLease acquireAiLane(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding) {
        DbSessionEntity session = requireSession(binding.ownerId(), binding.dbSessionId());
        final DbConnectionEntity configuration;
        final String existingHandle;
        synchronized (session) {
            requireAiTurn(binding);
            if (session.getAiLaneStatus() == DbLaneStatusEnum.BUSY) throw new DbSessionBusyException("数据库 AI 连接忙");
            if (session.getAiLaneStatus() == DbLaneStatusEnum.BROKEN || session.getAiLaneStatus() == DbLaneStatusEnum.CLOSED) {
                throw new IllegalStateException("数据库 AI 连接已失效，请重新打开工作台");
            }
            configuration = connectionRepository.find(binding.ownerId(), binding.dbConnectionId()).toBuilder()
                    .defaultDatabase(binding.targetDatabase()).build();
            existingHandle = session.getAiHandleId();
            session.setAiLaneStatus(DbLaneStatusEnum.BUSY);
        }
        String handle = existingHandle;
        try {
            if (handle == null) {
                requireTunnelOwnership(binding.ownerId(), configuration);
                IDbSessionPort.OpenedSession opened = sessionPort.open(configuration);
                handle = opened.handleId();
                if (!opened.autoCommit() || !"IDLE".equals(opened.transactionState())) {
                    throw new IllegalStateException("数据库 AI 连接未满足自动提交基线");
                }
                sessionPort.initializeAiSession(handle);
            } else if (binding.targetDatabase() != null) {
                sessionPort.selectDatabase(handle, binding.targetDatabase());
            }
            synchronized (session) {
                requireAiTurn(binding);
                if (Objects.equals(handle, session.getConsoleHandleId())) throw new IllegalStateException("数据库 AI 连接必须独立");
                session.setAiHandleId(handle);
                session.setLastActiveAt(now());
                var lease = new AiLaneLease(UUID.randomUUID().toString(), binding, handle);
                aiLaneOwners.put(binding.dbSessionId(), lease);
                return lease;
            }
        } catch (RuntimeException error) {
            if (handle != null && !Objects.equals(handle, session.getConsoleHandleId())) closeHandle(handle);
            synchronized (session) {
                aiLaneOwners.remove(binding.dbSessionId());
                session.setAiHandleId(null);
                if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                    session.setAiLaneStatus(existingHandle == null ? DbLaneStatusEnum.NOT_OPEN : DbLaneStatusEnum.BROKEN);
                }
            }
            throw error;
        }
    }

    public void releaseAiLane(AiLaneLease lease, boolean reusable) {
        var binding = lease.binding();
        String handle = lease.handleId();
        DbSessionEntity session = sessions.get(binding.dbSessionId());
        if (session == null) return;
        synchronized (session) {
            if (!Objects.equals(session.getOwnerId(), binding.ownerId())
                    || session.getSessionGeneration() != binding.sessionGeneration()
                    || !Objects.equals(session.getAiHandleId(), handle)
                    || !aiLaneOwners.remove(binding.dbSessionId(), lease)) return;
            if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                session.setAiLaneStatus(reusable ? DbLaneStatusEnum.READY : DbLaneStatusEnum.BROKEN);
            }
            session.setLastActiveAt(now());
            if (!reusable) { closeHandle(handle); session.setAiHandleId(null); }
        }
    }

    public DbSessionService(IDbConnectionRepository connectionRepository,
                            IDbSessionPort sessionPort,
                            ISqlAnalysisPort analysisPort,
                            DbSessionGovernanceProperties properties) {
        this(connectionRepository, sessionPort, analysisPort, properties,
                new SqlExecutionPolicy(), Clock.systemDefaultZone(), null);
    }

    /**
     * 保留给纯领域单测使用的构造器；生产 Spring Bean 使用带 SSH 归属校验的构造器。
     */
    public DbSessionService(IDbConnectionRepository connectionRepository,
                            IDbSessionPort sessionPort,
                            ISqlAnalysisPort analysisPort,
                            DbSessionGovernanceProperties properties,
                            SqlExecutionPolicy executionPolicy,
                            Clock clock) {
        this(connectionRepository, sessionPort, analysisPort, properties,
                executionPolicy, clock, null);
    }

    @Autowired
    public DbSessionService(IDbConnectionRepository connectionRepository,
                            IDbSessionPort sessionPort,
                            ISqlAnalysisPort analysisPort,
                            DbSessionGovernanceProperties properties,
                            ISshConnectionOwnershipService sshConnectionOwnershipService) {
        this(connectionRepository, sessionPort, analysisPort, properties,
                new SqlExecutionPolicy(), Clock.systemDefaultZone(), sshConnectionOwnershipService);
    }

    public DbSessionService(IDbConnectionRepository connectionRepository,
                            IDbSessionPort sessionPort,
                            ISqlAnalysisPort analysisPort,
                            DbSessionGovernanceProperties properties,
                            SqlExecutionPolicy executionPolicy,
                            Clock clock,
                            ISshConnectionOwnershipService sshConnectionOwnershipService) {
        this.connectionRepository = connectionRepository;
        this.sessionPort = sessionPort;
        this.analysisPort = analysisPort;
        this.properties = properties;
        this.resultCache = new DbExecutionResultCache(properties.getExecution());
        this.executionPolicy = executionPolicy;
        this.clock = clock;
        this.sshConnectionOwnershipService = sshConnectionOwnershipService;
    }

    @Override
    public DbSessionEntity open(String ownerId, String connectionId) {
        requireOwner(ownerId);
        final DbConnectionEntity connection;
        final DbSessionEntity entity;
        synchronized (lifecycleLock) {
            if (activeForOwner(ownerId) >= properties.getSession().getMaxSessionsPerOwner()
                    || activeSessions() >= properties.getSession().getMaxSessionsGlobal()) {
                throw new com.llf.ai.domain.db.adapter.port.DbSessionQuotaException("数据库工作台会话配额已用尽");
            }
            connection = connectionRepository.find(ownerId, connectionId);
            if (connection == null) throw new IllegalArgumentException("连接不存在");
            connection.validate();
            requireTunnelOwnership(ownerId, connection);
            LocalDateTime now = now();
            entity = DbSessionEntity.builder()
                    .dbSessionId("dbs_" + UUID.randomUUID())
                    .sessionGeneration(generation.incrementAndGet())
                    .ownerId(ownerId)
                    .connectionId(connectionId)
                    .tunnelSshConnectionId(connection.getTunnelSshConnectionId())
                    .configVersion(connection.getConfigVersion())
                    .currentDatabase(connection.getDefaultDatabase())
                    .targetContextVersion(0)
                    .consoleContextVersion(0)
                    .lifecycleStatus(DbSessionStatusEnum.OPENING)
                    .consoleLaneStatus(DbLaneStatusEnum.NOT_OPEN)
                    .aiLaneStatus(DbLaneStatusEnum.NOT_OPEN)
                    .createdAt(now)
                    .lastActiveAt(now)
                    .build();
            sessions.put(entity.getDbSessionId(), entity);
        }
        IDbSessionPort.OpenedSession opened = null;
        try {
            // 网络等待不持有全局协调锁；OPENING 记录已预占配额。
            opened = sessionPort.open(connection);
            synchronized (lifecycleLock) {
              synchronized (entity) {
                DbConnectionEntity latest = connectionRepository.find(ownerId, connectionId);
                if (entity.getLifecycleStatus() != DbSessionStatusEnum.OPENING
                        || latest == null || latest.getConfigVersion() != entity.getConfigVersion()
                        || !sessionPort.isTransportActive(opened.handleId())) {
                    throw new IllegalStateException("数据库配置或会话已变化，请重新打开工作台");
                }
                entity.setConsoleHandleId(opened.handleId());
                entity.setCurrentDatabase(opened.currentDatabase());
                entity.setAutoCommit(opened.autoCommit());
                entity.setTransactionState(opened.transactionState());
                entity.setSessionTimeZone(opened.sessionTimeZone());
                entity.setServerVersion(opened.serverVersion());
                entity.setTlsEncrypted(opened.tlsEncrypted());
                entity.setTlsIdentityVerified(opened.tlsIdentityVerified());
                entity.setCapabilityStates(opened.capabilityStates());
                entity.setConsoleLaneStatus(DbLaneStatusEnum.READY);
                entity.setLifecycleStatus(DbSessionStatusEnum.READY);
                return snapshot(entity);
              }
            }
        } catch (RuntimeException e) {
            if (opened != null) closeHandle(opened.handleId());
            synchronized (entity) {
                entity.setConsoleHandleId(null);
                entity.setConsoleLaneStatus(DbLaneStatusEnum.CLOSED);
                entity.setLifecycleStatus(DbSessionStatusEnum.CLOSED);
                sessions.remove(entity.getDbSessionId(), entity);
            }
            throw e;
        }
    }

    @Override
    public DbExecutionEntity prepare(String ownerId, String dbSessionId, String sql,
                                     long expectedTargetContextVersion,
                                     DbExecutionSourceEnum source) {
        if (source != DbExecutionSourceEnum.CONSOLE) throw new IllegalArgumentException("AI 必须使用独立的内部审批入口");
        return prepareInternal(ownerId, dbSessionId, sql, expectedTargetContextVersion, source, null);
    }

    public DbExecutionEntity prepareAi(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding, String sql) {
        return prepareInternal(binding.ownerId(), binding.dbSessionId(), sql, binding.targetContextVersion(),
                DbExecutionSourceEnum.AI, binding);
    }

    public record MetadataExecution(String handleId, DbExecutionEntity execution, int maxRows, int timeoutSeconds) { }

    /** 结构化诊断复用执行登记与 lane 生命周期；回调只由领域元数据服务提供。 */
    public DbExecutionEntity executeMetadata(String ownerId, String dbSessionId, DbResourceBinding binding,
            DbInspectionRequest request,
            java.util.function.Function<MetadataExecution, DbQueryResultEntity> operation) {
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        pruneExecutions();
        final DbExecutionEntity execution;
        final int maxRows;
        final int timeout;
        final String consoleHandle;
        synchronized (lifecycleLock) {
            synchronized (session) {
                if (binding != null && (!ownerId.equals(binding.ownerId()) || !dbSessionId.equals(binding.dbSessionId()))) {
                    throw new IllegalArgumentException("数据库资源绑定不一致");
                }
                if (binding != null) {
                    requireAiTurn(binding);
                    if (executions.values().stream().anyMatch(e -> e.getSource() == DbExecutionSourceEnum.AI
                            && dbSessionId.equals(e.getDbSessionId()) && !e.isTerminal())) {
                        throw new DbSessionBusyException("数据库 AI 已有待审批或运行操作");
                    }
                } else if (session.getConsoleLaneStatus() != DbLaneStatusEnum.READY) {
                    throw new DbSessionBusyException("数据库控制台连接正在执行其他操作");
                }
                DbConnectionEntity config = connectionRepository.find(ownerId, session.getConnectionId());
                if (session.getLifecycleStatus() != DbSessionStatusEnum.READY || config == null
                        || config.getConfigVersion() != session.getConfigVersion()) {
                    throw new IllegalStateException("数据库配置或会话已失效");
                }
                if (request.operation() == DbInspectionRequest.Operation.EXPLAIN) {
                    if (binding == null || request.query().getBytes(StandardCharsets.UTF_8).length > properties.getQuery().getMaxSqlBytes()) {
                        throw new IllegalArgumentException("执行计划需要可信 AI 绑定及有界查询");
                    }
                    var analysis = analysisPort.analyze(request.query());
                    var policy = executionPolicy.decide(analysis, DbExecutionSourceEnum.AI,
                            config.getAiDataMode(), config.getAiAllowedColumns(), binding.targetDatabase());
                    if (analysis.kind() != SqlStatementKind.QUERY || policy.action() != SqlPolicyAction.ALLOW) {
                        throw new IllegalArgumentException("执行计划查询未通过业务查询授权与语法检查");
                    }
                }
                requireExecutionCapacity(ownerId);
                maxRows = Math.min(request.limit(), binding == null
                        ? Math.min(config.getMaxRows(), properties.getQuery().getHardMaxRows())
                        : Math.min(50, properties.getAi().getMaxResultRows()));
                timeout = Math.min(config.getQueryTimeout(), properties.getQuery().getHardQueryTimeoutSeconds());
                execution = DbExecutionEntity.builder().executionId("dbx_" + UUID.randomUUID())
                        .ownerId(ownerId).dbSessionId(dbSessionId).sessionGeneration(session.getSessionGeneration())
                        .source(binding == null ? DbExecutionSourceEnum.CONSOLE : DbExecutionSourceEnum.AI)
                        .operationKind(DbOperationKind.METADATA).agentSessionId(binding == null ? null : binding.agentSessionId())
                        .turnId(binding == null ? null : binding.turnId()).statementKind(SqlStatementKind.QUERY)
                        .sqlHash(sha256(request.operation() + "|" + request.database() + "|" + request.table()
                                + "|" + request.limit() + "|" + request.query()))
                        .targetDatabase(request.database() == null ? session.getCurrentDatabase() : request.database())
                        .configVersion(session.getConfigVersion()).targetContextVersion(session.getTargetContextVersion())
                        .consoleContextVersion(session.getConsoleContextVersion()).state(DbExecutionState.PREPARED)
                        .expiresAt(now().plusSeconds(properties.getExecution().getPreparedTtlSeconds())).build();
                executions.put(execution.getExecutionId(), execution);
                consoleHandle = session.getConsoleHandleId();
                if (binding == null) session.setConsoleLaneStatus(DbLaneStatusEnum.BUSY);
                session.setLastActiveAt(now());
            }
        }
        AiLaneLease lease = null;
        boolean reusable = true;
        try {
            if (binding != null) lease = acquireAiLane(binding);
            synchronized (lifecycleLock) {
                synchronized (session) {
                    expireIfNeeded(execution);
                    if (execution.isTerminal()) return copyExecution(execution);
                    DbConnectionEntity latest = connectionRepository.find(ownerId, session.getConnectionId());
                    if (session.getLifecycleStatus() != DbSessionStatusEnum.READY || latest == null
                            || latest.getConfigVersion() != execution.getConfigVersion()
                            || session.getTargetContextVersion() != execution.getTargetContextVersion()) {
                        finish(execution, DbExecutionOutcome.CONTEXT_CHANGED, null);
                        return copyExecution(execution);
                    }
                    if (binding != null) requireAiTurn(binding);
                    execution.setState(DbExecutionState.RUNNING);
                    execution.setStartedAt(now());
                }
            }
            DbQueryResultEntity raw = operation.apply(new MetadataExecution(
                    binding == null ? consoleHandle : lease.handleId(), copyExecution(execution), maxRows, timeout));
            reusable = raw != null && raw.isSessionReusable()
                    && (binding == null || raw.isAutoCommit() && "IDLE".equals(raw.getTransactionState()));
            DbExecutionOutcome outcome = raw == null || !raw.isSessionReusable() ? DbExecutionOutcome.OUTCOME_UNKNOWN
                    : raw.getSafeError() == null ? DbExecutionOutcome.SUCCEEDED
                    : raw.isStatementCancelled() || Integer.valueOf(1317).equals(raw.getVendorCode()) ? DbExecutionOutcome.CANCELLED : DbExecutionOutcome.FAILED;
            DbQueryResultEntity result = binding == null ? raw
                    : new com.llf.ai.domain.db.service.output.DbAiOutputPolicy().sanitizeMetadata(raw, request.operation(),
                    properties.getAi().getMaxResultRows(), properties.getAi().getMaxCellBytes(), properties.getAi().getMaxResultBytes());
            synchronized (session) {
                if (result != null && result.getCapabilityState() != null
                        && (request.operation() == DbInspectionRequest.Operation.INSTANCE
                            || request.operation() == DbInspectionRequest.Operation.PROCESSES
                            || request.operation() == DbInspectionRequest.Operation.LOCKS)) {
                    var states = new java.util.HashMap<>(session.getCapabilityStates());
                    states.put(request.operation().name(), result.getCapabilityState());
                    session.setCapabilityStates(java.util.Map.copyOf(states));
                }
                finish(execution, outcome, result);
                return copyExecution(execution);
            }
        } catch (RuntimeException error) {
            synchronized (session) {
                if (!execution.isTerminal()) {
                    boolean started = execution.getState() == DbExecutionState.RUNNING;
                    if (started) reusable = false;
                    finish(execution, started ? DbExecutionOutcome.OUTCOME_UNKNOWN : DbExecutionOutcome.CONTEXT_CHANGED, null);
                }
                return copyExecution(execution);
            }
        } finally {
            if (lease != null) releaseAiLane(lease, reusable);
            if (binding == null) {
                synchronized (session) {
                    if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                        session.setConsoleLaneStatus(reusable ? DbLaneStatusEnum.READY : DbLaneStatusEnum.BROKEN);
                        if (!reusable) session.setLifecycleStatus(DbSessionStatusEnum.BROKEN);
                    }
                    session.setLastActiveAt(now());
                }
            }
        }
    }

    private void requireExecutionCapacity(String ownerId) {
        long ownerRecords = executions.values().stream().filter(e -> ownerId.equals(e.getOwnerId())).count();
        if (executions.size() >= properties.getExecution().getMaxRecordsGlobal()
                || ownerRecords >= properties.getExecution().getMaxRecordsPerOwner()) {
            throw new IllegalStateException("数据库执行记录配额已用尽");
        }
    }

    public DbExecutionEntity executeAi(com.llf.ai.domain.db.model.valobj.DbResourceBinding binding,
            String executionId, DbApprovalRegistry registry, DbApprovalRegistry.Ticket ticket) {
        DbSessionEntity session = requireSession(binding.ownerId(), binding.dbSessionId());
        DbExecutionEntity execution = requireExecution(binding.ownerId(), binding.dbSessionId(), executionId);
        synchronized (session) {
            if (execution.getSource() != DbExecutionSourceEnum.AI
                    || !Objects.equals(binding.agentSessionId(), execution.getAgentSessionId())
                    || !Objects.equals(binding.turnId(), execution.getTurnId())) {
                throw new IllegalArgumentException("AI 执行记录不属于当前轮次");
            }
            expireIfNeeded(execution);
            if (execution.isTerminal() || execution.getState() == DbExecutionState.RUNNING) {
                if (execution.getOutcome() == com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.EXPIRED
                        || execution.getOutcome() == com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED
                        || execution.getOutcome() == com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CONTEXT_CHANGED) endAiTurn(binding);
                return copyExecution(execution);
            }
        }
        AiLaneLease lease = acquireAiLane(binding);
        boolean reusable = true;
        try {
            final String sql;
            final ISqlAnalysisPort.Analysis analysis;
            final int timeout;
            synchronized (lifecycleLock) {
                synchronized (session) {
                    requireAiTurn(binding);
                    expireIfNeeded(execution);
                    if (execution.isTerminal()) return copyExecution(execution);
                    sql = execution.getSql();
                    analysis = analysisPort.analyze(sql);
                    DbConnectionEntity configuration = connectionRepository.find(binding.ownerId(), binding.dbConnectionId());
                    var decision = executionPolicy.decide(analysis, DbExecutionSourceEnum.AI,
                            configuration.getAiDataMode(), configuration.getAiAllowedColumns(), binding.targetDatabase());
                    if (decision.action() == SqlPolicyAction.DENY) {
                        finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.REJECTED, null);
                        return copyExecution(execution);
                    }
                    if (decision.action() == SqlPolicyAction.REQUIRE_APPROVAL
                            && (registry == null || ticket == null
                            || !registry.consume(ticket, binding, executionId, execution.getSqlHash()))) {
                        finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.REJECTED, null);
                        endAiTurn(binding);
                        return copyExecution(execution);
                    }
                    execution.setState(DbExecutionState.RUNNING);
                    execution.setStartedAt(now());
                    timeout = Math.min(properties.getQuery().getHardQueryTimeoutSeconds(), configuration.getQueryTimeout());
                }
            }
            DbQueryResultEntity result;
            com.llf.ai.domain.db.model.valobj.DbExecutionOutcome outcome;
            try {
                var raw = sessionPort.execute(lease.handleId(), executionId, sql,
                        Math.min(50, properties.getAi().getMaxResultRows()), timeout, true);
                boolean outcomeKnown = raw != null && raw.isSessionReusable();
                reusable = outcomeKnown && raw.isAutoCommit() && "IDLE".equals(raw.getTransactionState());
                outcome = !outcomeKnown ? com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.OUTCOME_UNKNOWN
                        : raw.getSafeError() == null ? com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.SUCCEEDED
                        : raw.isStatementCancelled() || Integer.valueOf(1317).equals(raw.getVendorCode()) ? com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED
                        : com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.FAILED;
                result = new com.llf.ai.domain.db.service.output.DbAiOutputPolicy().sanitize(raw, analysis,
                        properties.getAi().getMaxResultRows(), properties.getAi().getMaxCellBytes(), properties.getAi().getMaxResultBytes());
            } catch (RuntimeException error) {
                reusable = false;
                result = null;
                outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.OUTCOME_UNKNOWN;
            }
            synchronized (session) {
                finish(execution, outcome, result);
                return copyExecution(execution);
            }
        } finally {
            releaseAiLane(lease, reusable);
        }
    }

    private DbExecutionEntity prepareInternal(String ownerId, String dbSessionId, String sql,
            long expectedTargetContextVersion, DbExecutionSourceEnum source,
            com.llf.ai.domain.db.model.valobj.DbResourceBinding binding) {
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        pruneExecutions();
        synchronized (lifecycleLock) {
        synchronized (session) {
        requireExecutionCapacity(ownerId);
        if (binding != null) {
            requireAiTurn(binding);
            if (executions.values().stream().anyMatch(e -> e.getSource() == DbExecutionSourceEnum.AI
                    && dbSessionId.equals(e.getDbSessionId()) && !e.isTerminal())) {
                throw new DbSessionBusyException("数据库 AI 已有待审批或运行操作");
            }
        }
        if (expectedTargetContextVersion != session.getTargetContextVersion()) {
            throw new IllegalStateException("数据库目标上下文已变化，请重新准备 SQL");
        }
        if (sql == null || sql.getBytes(StandardCharsets.UTF_8).length > properties.getQuery().getMaxSqlBytes()) {
            throw new IllegalArgumentException("SQL 超出长度限制");
        }
        ISqlAnalysisPort.Analysis analysis = analysisPort.analyze(sql);
        DbConnectionEntity connection = connectionRepository.find(ownerId, session.getConnectionId());
        if (connection == null) throw new IllegalArgumentException("连接不存在");
        if (connection.getConfigVersion() != session.getConfigVersion()) throw new IllegalStateException("数据库配置已变化，请重新打开工作台");
        SqlPolicyDecision decision = executionPolicy.decide(
                analysis, source, connection.getAiDataMode(), connection.getAiAllowedColumns(),
                session.getCurrentDatabase());
        LocalDateTime now = now();
        DbExecutionEntity execution = DbExecutionEntity.builder()
                .executionId("dbx_" + UUID.randomUUID())
                .ownerId(ownerId)
                .dbSessionId(dbSessionId)
                .sessionGeneration(session.getSessionGeneration())
                .source(source)
                .agentSessionId(binding == null ? null : binding.agentSessionId())
                .turnId(binding == null ? null : binding.turnId())
                .operationKind(com.llf.ai.domain.db.model.valobj.DbOperationKind.SQL)
                .sqlHash(sha256(sql))
                .sql(sql)
                .targetDatabase(session.getCurrentDatabase())
                .configVersion(session.getConfigVersion())
                .targetContextVersion(session.getTargetContextVersion())
                .consoleContextVersion(session.getConsoleContextVersion())
                .statementKind(analysis.kind())
                .riskLevel(decision.riskLevel())
                .policyReason(String.join("；", decision.reasons()))
                .state(decision.action() == SqlPolicyAction.DENY
                        ? DbExecutionState.FINISHED : decision.action() == SqlPolicyAction.REQUIRE_APPROVAL
                        ? DbExecutionState.WAITING_APPROVAL : DbExecutionState.PREPARED)
                .outcome(decision.action() == SqlPolicyAction.DENY
                        ? com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.REJECTED : null)
                .resultAvailable(false)
                .expiresAt(now.plusSeconds(decision.action() == SqlPolicyAction.REQUIRE_APPROVAL
                        ? properties.getApproval().getTtlSeconds() : properties.getExecution().getPreparedTtlSeconds()))
                .build();
        if (execution.isTerminal()) {
            execution.setFinishedAt(now);
            execution.setSql(null);
        }
        executions.put(execution.getExecutionId(), execution);
        session.setLastActiveAt(now);
        return copyExecution(execution);
        }
        }
    }

    @Override
    public DbExecutionEntity execute(String ownerId, String dbSessionId, String executionId) {
        return dispatchConsole(ownerId, dbSessionId, executionId, null);
    }

    @Override
    public DbExecutionEntity executeAsync(String ownerId, String dbSessionId, String executionId) {
        if (consoleExecutor == null) throw new IllegalStateException("数据库异步执行器未配置");
        return dispatchConsole(ownerId, dbSessionId, executionId, consoleExecutor);
    }

    private DbExecutionEntity dispatchConsole(String ownerId, String dbSessionId, String executionId,
                                              java.util.concurrent.Executor executor) {
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        DbExecutionEntity execution = requireExecution(ownerId, dbSessionId, executionId);
        final String sql;
        final String handle;
        final int maxRows;
        final int timeout;
        synchronized (lifecycleLock) {
            synchronized (session) {
                if (execution.getSource() != DbExecutionSourceEnum.CONSOLE) {
                    throw new IllegalArgumentException("控制台不能消费 AI 执行记录");
                }
                if (execution.isTerminal() || execution.getState() == DbExecutionState.RUNNING) return copyExecution(execution);
                expireIfNeeded(execution);
                if (execution.isTerminal()) return copyExecution(execution);
                DbConnectionEntity connection = connectionRepository.find(ownerId, session.getConnectionId());
                if (connection == null || connection.getConfigVersion() != execution.getConfigVersion()
                        || execution.getSessionGeneration() != session.getSessionGeneration()
                        || execution.getTargetContextVersion() != session.getTargetContextVersion()
                        || execution.getConsoleContextVersion() != session.getConsoleContextVersion()) {
                    finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CONTEXT_CHANGED, null);
                    return copyExecution(execution);
                }
                if (session.getConsoleLaneStatus() == DbLaneStatusEnum.BUSY) {
                    throw new DbSessionBusyException("数据库控制台连接正在执行其他操作");
                }
                if (session.getLifecycleStatus() != DbSessionStatusEnum.READY || session.getConsoleHandleId() == null) {
                    throw new IllegalStateException("数据库工作台已失效");
                }
                if (execution.isCancelRequested()) {
                    finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED, null);
                    return copyExecution(execution);
                }
                execution.setState(DbExecutionState.RUNNING);
                execution.setStartedAt(now());
                session.setConsoleLaneStatus(DbLaneStatusEnum.BUSY);
                sql = execution.getSql();
                handle = session.getConsoleHandleId();
                maxRows = Math.min(properties.getQuery().getHardMaxRows(), connection.getMaxRows());
                timeout = Math.min(properties.getQuery().getHardQueryTimeoutSeconds(), connection.getQueryTimeout());
                if (executor != null) {
                    try {
                        executor.execute(() -> completeConsoleExecution(session, execution, handle, sql, maxRows, timeout));
                    } catch (java.util.concurrent.RejectedExecutionException rejected) {
                        execution.setState(DbExecutionState.PREPARED);
                        execution.setStartedAt(null);
                        session.setConsoleLaneStatus(DbLaneStatusEnum.READY);
                        throw new DbSessionBusyException("数据库执行容量已满，请稍后重试当前执行 ID");
                    }
                    return copyExecution(execution);
                }
            }
        }
        return completeConsoleExecution(session, execution, handle, sql, maxRows, timeout);
    }

    private DbExecutionEntity completeConsoleExecution(DbSessionEntity session, DbExecutionEntity execution,
            String handle, String sql, int maxRows, int timeout) {
        synchronized (session) {
            checkTransport(session);
            if (execution.isCancelRequested() || session.getLifecycleStatus() != DbSessionStatusEnum.READY) {
                sessionPort.discardCancellation(handle, execution.getExecutionId());
                finish(execution, DbExecutionOutcome.CANCELLED, null);
                if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) session.setConsoleLaneStatus(DbLaneStatusEnum.READY);
                return copyExecution(execution);
            }
        }
        DbQueryResultEntity result = null;
        com.llf.ai.domain.db.model.valobj.DbExecutionOutcome outcome;
        try {
            result = sessionPort.execute(handle, execution.getExecutionId(), sql, maxRows, timeout, session.isAutoCommit());
            if (result == null || !result.isSessionReusable()) {
                outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.OUTCOME_UNKNOWN;
            } else if (result.getSafeError() == null) {
                outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.SUCCEEDED;
            } else if (result.isStatementCancelled() || Integer.valueOf(1317).equals(result.getVendorCode())) {
                outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED;
            } else {
                outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.FAILED;
            }
        } catch (RuntimeException e) {
            // 已进入驱动的异常不能证明写入失败，也不能重试。
            outcome = com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.OUTCOME_UNKNOWN;
        }
        synchronized (session) {
            finish(execution, outcome, result);
            session.setConsoleContextVersion(session.getConsoleContextVersion() + 1);
            if (result != null) {
                session.setAutoCommit(result.isAutoCommit());
                session.setTransactionState(result.getTransactionState());
                if (result.getSessionTimeZone() != null) {
                    session.setSessionTimeZone(result.getSessionTimeZone());
                    if (!Objects.equals(session.getCurrentDatabase(), result.getCurrentDatabase())) {
                        session.setCurrentDatabase(result.getCurrentDatabase());
                        session.setTargetContextVersion(session.getTargetContextVersion() + 1);
                        invalidateContextExecutions(session);
                    }
                }
            }
            if ((result == null || !result.isSessionReusable()) && session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                session.setConsoleLaneStatus(DbLaneStatusEnum.BROKEN);
                session.setLifecycleStatus(DbSessionStatusEnum.BROKEN);
            } else if (session.getLifecycleStatus() == DbSessionStatusEnum.READY) {
                session.setConsoleLaneStatus(DbLaneStatusEnum.READY);
            }
            session.setLastActiveAt(now());
            return copyExecution(execution);
        }
    }

    @Override
    public DbExecutionEntity cancel(String ownerId, String dbSessionId, String executionId) {
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        DbExecutionEntity execution = requireExecution(ownerId, dbSessionId, executionId);
        String handle = null;
        synchronized (session) {
            if (execution.isTerminal()) return copyExecution(execution);
            execution.setCancelRequested(true);
            if (execution.getState() == DbExecutionState.PREPARED
                    || execution.getState() == DbExecutionState.WAITING_APPROVAL) {
                finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED, null);
            } else if (execution.getState() == DbExecutionState.RUNNING) {
                handle = execution.getSource() == DbExecutionSourceEnum.CONSOLE
                        ? session.getConsoleHandleId() : session.getAiHandleId();
            }
        }
        if (handle != null) sessionPort.cancel(handle, executionId);
        synchronized (session) {
            if (handle != null && execution.isTerminal()) sessionPort.discardCancellation(handle, executionId);
            return copyExecution(execution);
        }
    }

    @Override
    public DbExecutionEntity getExecution(String ownerId, String dbSessionId, String executionId) {
        requireOwner(ownerId);
        DbExecutionEntity execution = requireExecution(ownerId, dbSessionId, executionId);
        DbSessionEntity session = sessions.get(dbSessionId);
        if (session == null) return copyExecution(execution);
        synchronized (session) {
            expireIfNeeded(execution);
            return copyExecution(execution);
        }
    }

    @Override
    public DbSessionEntity selectDatabase(String ownerId, String dbSessionId, String database,
                                          long expectedTargetContextVersion) {
        DbSessionEntity session = requireSession(ownerId, dbSessionId);
        if (database == null || !database.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException("数据库标识符不合法");
        }
        synchronized (session) {
            if (expectedTargetContextVersion != session.getTargetContextVersion()) {
                throw new IllegalStateException("数据库目标上下文已变化，请刷新后重试");
            }
            if (session.getConsoleLaneStatus() == DbLaneStatusEnum.BUSY) {
                throw new DbSessionBusyException("数据库控制台连接正在执行其他操作");
            }
            sessionPort.selectDatabase(session.getConsoleHandleId(), database);
            session.setCurrentDatabase(database);
            session.setTargetContextVersion(session.getTargetContextVersion() + 1);
            session.setConsoleContextVersion(session.getConsoleContextVersion() + 1);
            session.setLastActiveAt(now());
            invalidateContextExecutions(session);
            return snapshot(session);
        }
    }

    @Override
    public void close(String ownerId, String dbSessionId) {
        requireOwner(ownerId);
        DbSessionEntity session = dbSessionId == null ? null : sessions.get(dbSessionId);
        if (session == null || !Objects.equals(ownerId, session.getOwnerId())) {
            throw new IllegalArgumentException("数据库工作台会话不存在");
        }
        record PendingCancel(String handle, String executionId) { }
        List<PendingCancel> cancellations = new ArrayList<>();
        String consoleHandle;
        String aiHandle;
        synchronized (session) {
            if (session.getLifecycleStatus() == DbSessionStatusEnum.CLOSED
                    || session.getLifecycleStatus() == DbSessionStatusEnum.CLOSING) return;
            boolean opening = session.getLifecycleStatus() == DbSessionStatusEnum.OPENING;
            session.setLifecycleStatus(DbSessionStatusEnum.CLOSING);
            if (approvalRegistry != null) approvalRegistry.invalidateSession(ownerId, dbSessionId, session.getSessionGeneration());
            aiTurns.remove(dbSessionId);
            aiLaneOwners.remove(dbSessionId);
            // 在途建连仍占配额，由 open 的完成路径负责释放。
            if (opening) return;
            consoleHandle = session.getConsoleHandleId();
            aiHandle = session.getAiHandleId();
            for (DbExecutionEntity execution : executions.values()) {
                if (Objects.equals(execution.getDbSessionId(), dbSessionId) && !execution.isTerminal()) {
                    execution.setCancelRequested(true);
                    if (execution.getState() == DbExecutionState.RUNNING) {
                        cancellations.add(new PendingCancel(execution.getSource() == DbExecutionSourceEnum.CONSOLE
                                ? consoleHandle : aiHandle, execution.getExecutionId()));
                    } else {
                        finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CANCELLED, null);
                    }
                }
            }
        }
        // 精确取消与网络关闭不持有会话锁，执行收尾和状态查询可以继续。
        for (PendingCancel cancellation : cancellations) {
            if (cancellation.handle() == null) continue;
            try { sessionPort.cancel(cancellation.handle(), cancellation.executionId()); }
            catch (RuntimeException failure) {
                log.warn("关闭工作台时取消失败 executionId={} exceptionType={}", cancellation.executionId(), failure.getClass().getSimpleName());
            }
        }
        closeHandle(consoleHandle);
        closeHandle(aiHandle);
        synchronized (session) {
            session.setConsoleHandleId(null);
            session.setAiHandleId(null);
            session.setConsoleLaneStatus(DbLaneStatusEnum.CLOSED);
            session.setAiLaneStatus(DbLaneStatusEnum.CLOSED);
            session.setLifecycleStatus(DbSessionStatusEnum.CLOSED);
            sessions.remove(dbSessionId, session);
        }
    }

    @Override
    public List<DbSessionEntity> list(String ownerId) {
        requireOwner(ownerId);
        return sessions.values().stream()
                .filter(session -> Objects.equals(ownerId, session.getOwnerId()) && session.getLifecycleStatus() != DbSessionStatusEnum.CLOSED)
                .map(session -> { synchronized (session) { checkTransport(session); return snapshot(session); } })
                .toList();
    }

    /** 供元数据服务和 Agent 绑定使用；不把 handle ID 放入 API DTO。 */
    public DbSessionEntity requireSession(String ownerId, String dbSessionId) {
        requireOwner(ownerId);
        DbSessionEntity session = dbSessionId == null ? null : sessions.get(dbSessionId);
        if (session == null || !Objects.equals(ownerId, session.getOwnerId())) {
            throw new IllegalArgumentException("数据库工作台会话不存在");
        }
        synchronized (session) {
            checkTransport(session);
            if (session.getLifecycleStatus() == DbSessionStatusEnum.BROKEN) {
                throw new IllegalStateException("数据库工作台会话已失效，请重新打开");
            }
            if (!session.isOpen()) throw new IllegalArgumentException("数据库工作台会话已关闭");
        }
        return session;
    }

    /** 调用方持有 session monitor；失效只关闭准入，网络清理由关闭/回收路径负责。 */
    private void checkTransport(DbSessionEntity session) {
        if (session.getLifecycleStatus() == DbSessionStatusEnum.READY
                && !sessionPort.isTransportActive(session.getConsoleHandleId())) invalidateSession(session);
    }

    private void invalidateSession(DbSessionEntity session) {
        session.setLifecycleStatus(DbSessionStatusEnum.BROKEN);
        if (approvalRegistry != null) approvalRegistry.invalidateSession(
                session.getOwnerId(), session.getDbSessionId(), session.getSessionGeneration());
        aiTurns.remove(session.getDbSessionId());
        for (DbExecutionEntity execution : executions.values()) {
            if (Objects.equals(session.getDbSessionId(), execution.getDbSessionId())
                    && !execution.isTerminal() && execution.getState() != DbExecutionState.RUNNING) {
                finish(execution, DbExecutionOutcome.CONTEXT_CHANGED, null);
            }
        }
    }

    @Override
    public int reapIdleSessions() {
        pruneExecutions();
        LocalDateTime cutoff = now().minusNanos(properties.getSession().getIdleTimeoutMillis() * 1_000_000L);
        int count = 0;
        for (DbSessionEntity session : sessions.values()) {
            boolean broken;
            synchronized (session) {
                checkTransport(session);
                broken = session.getLifecycleStatus() == DbSessionStatusEnum.BROKEN;
            }
            if (broken || (session.getLastActiveAt() != null && session.getLastActiveAt().isBefore(cutoff)
                    && session.getConsoleLaneStatus() != DbLaneStatusEnum.BUSY
                    && !hasActiveExecution(session.getDbSessionId()))) {
                try {
                    close(session.getOwnerId(), session.getDbSessionId());
                    count++;
                } catch (RuntimeException e) {
                    log.warn("回收数据库会话失败 dbSessionId={} exceptionType={}",
                            session.getDbSessionId(), e.getClass().getSimpleName());
                }
            }
        }
        return count;
    }

    private void pruneExecutions() {
        LocalDateTime cutoff = now().minusSeconds(properties.getExecution().getResultTtlSeconds());
        for (DbExecutionEntity execution : executions.values()) {
            DbSessionEntity session = sessions.get(execution.getDbSessionId());
            Object lock = session == null ? execution : session;
            synchronized (lock) {
                expireIfNeeded(execution);
                if (execution.isTerminal() && execution.getFinishedAt() != null
                        && execution.getFinishedAt().isBefore(cutoff)) {
                    executions.remove(execution.getExecutionId(), execution);
                    resultCache.remove(execution.getExecutionId());
                }
            }
        }
    }

    private void refreshSessionState(DbSessionEntity session) {
        IDbSessionPort.SessionState state = sessionPort.state(session.getConsoleHandleId());
        session.setAutoCommit(state.autoCommit());
        session.setTransactionState(state.transactionState());
        session.setSessionTimeZone(state.sessionTimeZone());
    }

    private void invalidateContextExecutions(DbSessionEntity session) {
        for (DbExecutionEntity execution : executions.values()) {
            if (Objects.equals(execution.getDbSessionId(), session.getDbSessionId())
                    && !execution.isTerminal() && execution.getState() != DbExecutionState.RUNNING
                    && execution.getTargetContextVersion() != session.getTargetContextVersion()) {
                finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.CONTEXT_CHANGED, null);
            }
        }
    }

    private boolean hasActiveExecution(String dbSessionId) {
        return executions.values().stream().anyMatch(execution ->
                Objects.equals(dbSessionId, execution.getDbSessionId()) && !execution.isTerminal());
    }

    private void expireIfNeeded(DbExecutionEntity execution) {
        if ((execution.getState() == DbExecutionState.PREPARED || execution.getState() == DbExecutionState.WAITING_APPROVAL) && execution.getExpiresAt() != null
                && execution.getExpiresAt().isBefore(now())) {
            finish(execution, com.llf.ai.domain.db.model.valobj.DbExecutionOutcome.EXPIRED, null);
        }
    }

    private void finish(DbExecutionEntity execution,
                        com.llf.ai.domain.db.model.valobj.DbExecutionOutcome outcome,
                        DbQueryResultEntity result) {
        execution.setOutcome(outcome);
        execution.setState(DbExecutionState.FINISHED);
        execution.setFinishedAt(now());
        if (result != null) {
            resultCache.put(execution.getExecutionId(), execution.getOwnerId(), result);
            execution.setResult(DbExecutionResultCache.summary(result));
            execution.setResultAvailable(true);
        }
        execution.setSql(null);
    }

    private int connectionMaxRows(String ownerId, String connectionId) {
        DbConnectionEntity entity = connectionRepository.find(ownerId, connectionId);
        return entity == null || entity.getMaxRows() == null
                ? properties.getQuery().getDefaultMaxRows() : entity.getMaxRows();
    }

    private DbExecutionEntity requireExecution(String ownerId, String dbSessionId, String executionId) {
        DbExecutionEntity execution = executionId == null ? null : executions.get(executionId);
        if (execution == null || !Objects.equals(ownerId, execution.getOwnerId())
                || !Objects.equals(dbSessionId, execution.getDbSessionId())) {
            throw new IllegalArgumentException("数据库执行记录不存在");
        }
        return execution;
    }

    private void closeHandle(String handleId) {
        if (handleId != null) {
            try {
                sessionPort.close(handleId);
            } catch (RuntimeException e) {
                log.warn("关闭数据库物理连接失败 handleId={} exceptionType={}",
                        handleId, e.getClass().getSimpleName());
            }
        }
    }

    private void requireTunnelOwnership(String ownerId, DbConnectionEntity connection) {
        String tunnelConnectionId = connection.getTunnelSshConnectionId();
        if (tunnelConnectionId == null || tunnelConnectionId.isBlank()) return;
        if (sshConnectionOwnershipService == null) {
            throw new IllegalStateException("数据库 SSH 隧道归属校验未配置");
        }
        sshConnectionOwnershipService.requireOwnership(ownerId, tunnelConnectionId);
    }

    private long activeForOwner(String ownerId) {
        return sessions.values().stream().filter(session -> session.getLifecycleStatus() != DbSessionStatusEnum.CLOSED
                && Objects.equals(ownerId, session.getOwnerId())).count();
    }

    private long activeSessions() {
        return sessions.values().stream().filter(session -> session.getLifecycleStatus() != DbSessionStatusEnum.CLOSED).count();
    }

    private DbSessionEntity snapshot(DbSessionEntity entity) {
        return DbSessionEntity.builder()
                .dbSessionId(entity.getDbSessionId())
                .sessionGeneration(entity.getSessionGeneration())
                .ownerId(entity.getOwnerId())
                .connectionId(entity.getConnectionId())
                .configVersion(entity.getConfigVersion())
                .currentDatabase(entity.getCurrentDatabase())
                .targetContextVersion(entity.getTargetContextVersion())
                .consoleContextVersion(entity.getConsoleContextVersion())
                .consoleLaneStatus(entity.getConsoleLaneStatus())
                .aiLaneStatus(entity.getAiLaneStatus())
                .lifecycleStatus(entity.getLifecycleStatus())
                .autoCommit(entity.isAutoCommit())
                .transactionState(entity.getTransactionState())
                .sessionTimeZone(entity.getSessionTimeZone())
                .serverVersion(entity.getServerVersion())
                .tlsEncrypted(entity.isTlsEncrypted())
                .tlsIdentityVerified(entity.isTlsIdentityVerified())
                .capabilityStates(entity.getCapabilityStates())
                .createdAt(entity.getCreatedAt())
                .lastActiveAt(entity.getLastActiveAt())
                .build();
    }

    private DbExecutionEntity copyExecution(DbExecutionEntity entity) {
        DbQueryResultEntity retainedResult = resultCache.get(entity.getExecutionId());
        return DbExecutionEntity.builder()
                .executionId(entity.getExecutionId())
                .ownerId(entity.getOwnerId())
                .dbSessionId(entity.getDbSessionId())
                .sessionGeneration(entity.getSessionGeneration())
                .source(entity.getSource())
                .operationKind(entity.getOperationKind())
                .agentSessionId(entity.getAgentSessionId())
                .turnId(entity.getTurnId())
                .sqlHash(entity.getSqlHash())
                .targetDatabase(entity.getTargetDatabase())
                .configVersion(entity.getConfigVersion())
                .targetContextVersion(entity.getTargetContextVersion())
                .consoleContextVersion(entity.getConsoleContextVersion())
                .statementKind(entity.getStatementKind())
                .riskLevel(entity.getRiskLevel())
                .policyReason(entity.getPolicyReason())
                .state(entity.getState())
                .outcome(entity.getOutcome())
                .cancelRequested(entity.isCancelRequested())
                .resultAvailable(retainedResult != null)
                .expiresAt(entity.getExpiresAt())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .result(retainedResult == null ? entity.getResult() : retainedResult)
                .build();
    }

    private String requireOwner(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("设备身份不能为空");
        return value;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", e);
        }
    }
}
