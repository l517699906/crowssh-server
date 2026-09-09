package com.llf.ai.infrastructure.adapter.port.db;

import com.llf.ai.domain.db.adapter.port.DbTargetBlockedException;
import com.llf.ai.domain.db.adapter.port.IDbMetadataPort;
import com.llf.ai.domain.db.adapter.port.IDbSessionPort;
import com.llf.ai.domain.db.adapter.port.IDbTunnelPort;
import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.valobj.SslModeEnum;
import com.llf.ai.infrastructure.security.DbOutboundPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MysqlSessionPort implements IDbSessionPort, IDbMetadataPort {
    private static final String SQL_MODE_BASELINE = "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";
    private final JdbcUrlFactory urlFactory;
    private final com.llf.ai.infrastructure.security.PasswordEncryptor passwordEncryptor;
    private final DbOutboundPolicy outboundPolicy;
    private final IDbTunnelPort tunnelPort;
    private final int maxCellBytes;
    private final int maxResultBytes;
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "db-deadline"); thread.setDaemon(true); return thread;
    });
    private final Map<String, ManagedConnection> connections = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private volatile boolean shuttingDown;

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        List<String> handles;
        synchronized (lifecycle) {
            if (shuttingDown) return;
            shuttingDown = true;
            handles = List.copyOf(connections.keySet());
        }
        try {
            for (String handle : handles) {
                try { close(handle); }
                catch (RuntimeException failure) {
                    log.warn("销毁数据库连接清理失败 exceptionType={}", failure.getClass().getSimpleName());
                }
            }
        } finally {
            synchronized (lifecycle) { deadlines.shutdown(); }
        }
    }

    public MysqlSessionPort(
            JdbcUrlFactory urlFactory,
            com.llf.ai.infrastructure.security.PasswordEncryptor passwordEncryptor,
            DbOutboundPolicy outboundPolicy,
            IDbTunnelPort tunnelPort,
            @Value("${crowssh.db.query.max-cell-bytes:65536}") int maxCellBytes,
            @Value("${crowssh.db.query.max-result-bytes:8388608}") int maxResultBytes) {
        this.urlFactory = urlFactory;
        this.passwordEncryptor = passwordEncryptor;
        this.outboundPolicy = outboundPolicy;
        this.tunnelPort = tunnelPort;
        this.maxCellBytes = maxCellBytes;
        this.maxResultBytes = maxResultBytes;
    }

    @Override
    public OpenedSession open(DbConnectionEntity configuration) {
        var opened = open(configuration, DbPhysicalConnectionQuota.Kind.DATA);
        try {
            var probe = probeCapabilities(opened.handleId());
            if (!probe.reusable()) throw new IllegalStateException("数据库能力探测后连接不可复用");
            return new OpenedSession(opened.handleId(), opened.currentDatabase(), opened.autoCommit(),
                    opened.transactionState(), opened.sessionTimeZone(), opened.serverVersion(),
                    opened.tlsEncrypted(), opened.tlsIdentityVerified(), probe.states());
        } catch (RuntimeException failure) {
            close(opened.handleId());
            throw failure;
        }
    }

    private OpenedSession open(DbConnectionEntity configuration, DbPhysicalConnectionQuota.Kind kind) {
        if (shuttingDown) throw new IllegalStateException("数据库服务正在关闭");
        configuration.validate();
        IDbTunnelPort.TunnelLease lease = null;
        JdbcUrlFactory.ConnectionSpec spec = null;
        Connection connection = null;
        try {
            String actualHost;
            int actualPort;
            if (configuration.getTunnelSshConnectionId() != null
                    && !configuration.getTunnelSshConnectionId().isBlank()) {
                lease = tunnelPort.acquire(configuration.getUserId(), configuration.getTunnelSshConnectionId(),
                        configuration.getHost(), configuration.getPort());
                actualHost = lease.localHost();
                actualPort = lease.localPort();
            } else {
                try {
                    actualHost = outboundPolicy.resolveDirectAddress(configuration.getHost()).getHostAddress();
                } catch (java.net.UnknownHostException e) {
                    throw new IllegalStateException("数据库主机解析失败", e);
                }
                actualPort = configuration.getPort();
            }
            if (configuration.getPassword() == null && configuration.getPasswordCiphertext() != null) {
                configuration.setPassword(passwordEncryptor.decrypt(configuration.getPasswordCiphertext()));
            }
            try { spec = urlFactory.create(configuration, actualHost, actualPort, kind); }
            finally { configuration.setPassword(null); }
            connection = DriverManager.getConnection(spec.url(), spec.properties());
            spec.properties().remove("password");
            connection.setAutoCommit(true);
            // 控制台与AI均固定解析相关模式，不能让服务器ANSI/反斜杠设置改变已校验SQL的含义。
            try (Statement baseline = connection.createStatement()) {
                baseline.setQueryTimeout(10);
                baseline.execute("SET SESSION sql_mode='" + SQL_MODE_BASELINE + "'");
                try (ResultSet mode = baseline.executeQuery("SELECT @@SESSION.sql_mode")) {
                    if (!mode.next() || !matchesSqlModeBaseline(mode.getString(1))) {
                        throw new SQLException("数据库 SQL 模式未满足解析基线");
                    }
                }
            }
            ManagedConnection managed = new ManagedConnection(
                    "dbh_" + UUID.randomUUID(), connection, lease,
                    configuration.getDefaultDatabase(), configuration.getSslMode(),
                    configuration.getTlsServerName(), spec);
            try (Statement probe = connection.createStatement(); ResultSet rows = probe.executeQuery("SELECT @@performance_schema")) {
                if (rows.next()) managed.performanceSchemaEnabled = rows.getBoolean(1);
            } catch (SQLException ignored) {
                // 单项诊断探测失败不阻止普通数据库连接。
            }
            OpenedSession opened = new OpenedSession(managed.handleId, currentDatabase(managed),
                    safeAutoCommit(connection), transactionState(connection), sessionTimeZone(connection),
                    connection.getMetaData().getDatabaseProductVersion(),
                    isTlsEncrypted(connection), isTlsIdentityVerified(configuration));
            synchronized (lifecycle) {
                if (shuttingDown) throw new IllegalStateException("数据库服务正在关闭");
                connections.put(managed.handleId, managed);
            }
            spec = null;
            connection = null;
            return opened;
        } catch (DbTargetBlockedException e) {
            closeQuietly(connection);
            cleanupSpec(spec);
            if (lease != null) tunnelPort.release(lease.leaseId());
            throw e;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            cleanupSpec(spec);
            if (lease != null) tunnelPort.release(lease.leaseId());
            if (isTlsFailure(e)) throw new com.llf.ai.domain.db.adapter.port.DbTlsVerificationException(safeConnectionMessage(e), e);
            throw new IllegalStateException(safeConnectionMessage(e), e);
        }
    }

    @Override
    public void close(String handleId) {
        Object closeDeadlineLock = new Object();
        var closeCompleted = new java.util.concurrent.atomic.AtomicBoolean();
        ManagedConnection managed;
        java.util.concurrent.ScheduledFuture<?> closeDeadline;
        synchronized (lifecycle) {
            managed = connections.remove(handleId);
            if (managed == null) return;
            closeDeadline = deadlines.schedule(() -> {
                synchronized (closeDeadlineLock) {
                    if (!closeCompleted.get()) PinnedMysqlSocketFactory.abort(
                            managed.spec.properties().getProperty(PinnedMysqlSocketFactory.ROUTE_PROPERTY));
                }
            }, 5, java.util.concurrent.TimeUnit.SECONDS);
        }
        try {
            managed.statements.values().forEach(this::closeQuietly);
            if ("ACTIVE".equals(transactionState(managed.connection))) {
                try (Statement rollback = managed.connection.createStatement()) { rollback.execute("ROLLBACK"); }
            }
            managed.connection.close();
        } catch (SQLException e) {
            log.debug("关闭 MySQL 连接失败 handleId={} exceptionType={}", handleId, e.getClass().getSimpleName());
        } finally {
            closeQuietly(managed.connection);
            try {
                try { managed.spec.cleanup(); }
                finally { if (managed.lease != null) tunnelPort.release(managed.lease.leaseId()); }
            } finally {
                synchronized (closeDeadlineLock) { closeCompleted.set(true); closeDeadline.cancel(false); }
            }
        }
    }

    @Override
    public DbQueryResultEntity execute(String handleId, String executionId, String sql,
                                       int maxRows, int queryTimeoutSeconds, boolean autoCommit) {
        return executeInternal(handleId, executionId, sql, null, maxRows, queryTimeoutSeconds);
    }

    @Override
    public void initializeAiSession(String handleId) {
        // 固定双引号/反斜杠及聚合语义，与服务端解析策略一致，不继承控制台设置。
        DbQueryResultEntity configured = executeInternal(handleId, "baseline_" + UUID.randomUUID(),
                "SET SESSION sql_mode='" + SQL_MODE_BASELINE + "', time_zone='+00:00', autocommit=1", null, 1, 10);
        if (configured.getSafeError() != null || !configured.isSessionReusable()
                || !configured.isAutoCommit() || !"IDLE".equals(configured.getTransactionState())) {
            throw new IllegalStateException("无法初始化数据库 AI 会话基线");
        }
        DbQueryResultEntity verified = executeInternal(handleId, "baseline_check_" + UUID.randomUUID(),
                "SELECT @@SESSION.sql_mode, @@SESSION.time_zone, @@SESSION.autocommit", null, 1, 10);
        if (verified.getSafeError() != null || !verified.isSessionReusable() || verified.getRows().size() != 1
                || verified.getRows().get(0).size() != 3) throw new IllegalStateException("无法验证数据库 AI 会话基线");
        List<String> values = verified.getRows().get(0);
        if (!matchesSqlModeBaseline(values.get(0))
                || !"+00:00".equals(values.get(1)) || !"1".equals(values.get(2))) {
            throw new IllegalStateException("数据库 AI 会话基线不匹配");
        }
    }

    static boolean matchesSqlModeBaseline(String modes) {
        return modes != null && new java.util.HashSet<>(java.util.Arrays.asList(SQL_MODE_BASELINE.split(",")))
                .equals(new java.util.HashSet<>(java.util.Arrays.asList(modes.split(","))));
    }

    private DbQueryResultEntity executeInternal(String handleId, String executionId, String sql,
            List<String> parameters, int maxRows, int queryTimeoutSeconds) {
        ManagedConnection managed = requireHandle(handleId);
        Statement statement = null;
        long started = System.currentTimeMillis();
        Object deadlineLock = new Object();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        var deadline = deadlines.schedule(() -> {
            synchronized (deadlineLock) {
                if (!completed.get()) PinnedMysqlSocketFactory.abort(
                        managed.spec.properties().getProperty(PinnedMysqlSocketFactory.ROUTE_PROPERTY));
            }
        },
                Math.max(1, Math.min(queryTimeoutSeconds, 150)), java.util.concurrent.TimeUnit.SECONDS);
        try {
            if (parameters == null) {
                statement = managed.connection.createStatement();
            } else {
                PreparedStatement prepared = managed.connection.prepareStatement(sql);
                statement = prepared;
                for (int i = 0; i < parameters.size(); i++) prepared.setString(i + 1, parameters.get(i));
            }
            statement.setFetchSize(Integer.MIN_VALUE);
            statement.setMaxRows(Math.min(maxRows, 5000) + 1);
            synchronized (managed.cancelLock) {
                if (managed.cancelled.remove(executionId)) {
                    throw new SQLException("执行已取消", "70100", 1317);
                }
                managed.statements.put(executionId, statement);
            }
            boolean hasResult = parameters == null ? statement.execute(sql) : ((PreparedStatement) statement).execute();
            DbQueryResultEntity result;
            if (hasResult) {
                try (ResultSet rows = statement.getResultSet()) {
                    result = readRows(rows, null, maxRows, started, managed.connection);
                }
                // 流式结果的 JDBC warning 链可能为空；在状态查询覆盖诊断区前读取有界警告码。
                try (Statement warningQuery = managed.connection.createStatement()) {
                    warningQuery.setMaxRows(Math.min(maxRows, 5000) + 1);
                    List<String> safeWarnings = new ArrayList<>();
                    try (ResultSet warningRows = warningQuery.executeQuery("SHOW WARNINGS LIMIT 20")) {
                        while (warningRows.next()) safeWarnings.add("数据库警告 code=" + warningRows.getInt(2));
                    }
                    result.setWarnings(safeWarnings);
                }
            } else {
                result = updateResult(statement.getLargeUpdateCount(), statement.getWarnings(), started, managed.connection);
            }
            if (statement.getMoreResults()) {
                result.setSessionReusable(false);
                result.setTruncated(true);
                result.getTruncationReasons().add("MULTI_RESULT_REJECTED");
            }
            // 查询服务器实际会话状态，包含带注释 USE 和 SET 的执行效果。
            try (Statement stateQuery = managed.connection.createStatement();
                 ResultSet stateRows = stateQuery.executeQuery("SELECT DATABASE(), @@session.time_zone")) {
                if (stateRows.next()) {
                    managed.currentDatabase = stateRows.getString(1);
                    result.setCurrentDatabase(managed.currentDatabase);
                    result.setSessionTimeZone(stateRows.getString(2));
                } else result.setSessionReusable(false);
            }
            result.setAutoCommit(safeAutoCommit(managed.connection));
            result.setTransactionState(transactionState(managed.connection));
            return result;
        } catch (SQLException e) {
            return DbQueryResultEntity.builder()
                    .statementCancelled(e instanceof com.mysql.cj.jdbc.exceptions.MySQLStatementCancelledException
                            || e.getErrorCode() == 1317)
                    .safeError(safeSqlError(e))
                    .sqlState(e.getSQLState())
                    .vendorCode(e.getErrorCode())
                    .executionMillis(Math.max(0, System.currentTimeMillis() - started))
                    .totalMillis(Math.max(0, System.currentTimeMillis() - started))
                    .autoCommit(safeAutoCommit(managed.connection))
                    .transactionState(transactionState(managed.connection))
                    .sessionReusable(e.getSQLState() == null || !e.getSQLState().startsWith("08"))
                    .build();
        } finally {
            synchronized (managed.cancelLock) {
                managed.statements.remove(executionId, statement);
                managed.cancelled.remove(executionId);
            }
            closeQuietly(statement);
            // 等待已启动的截止回调结束，再允许领域释放 lane，避免迟到关闭影响下一条语句。
            synchronized (deadlineLock) {
                completed.set(true);
                deadline.cancel(false);
            }
        }
    }

    @Override
    public boolean isTransportActive(String handleId) {
        ManagedConnection managed = connections.get(handleId);
        return managed != null && (managed.lease == null || tunnelPort.isActive(managed.lease.leaseId()));
    }

    @Override
    public boolean cancel(String handleId, String executionId) {
        ManagedConnection managed = connections.get(handleId);
        if (managed == null) return false;
        synchronized (managed.cancelLock) {
            Statement statement = managed.statements.get(executionId);
            if (statement == null) {
                // 取消可以早于 Statement 注册；完成后的执行由领域终态阻止进入此入口。
                managed.cancelled.add(executionId);
                return false;
            }
            try {
                Object cancelDeadlineLock = new Object();
                var cancelCompleted = new java.util.concurrent.atomic.AtomicBoolean();
                var cancelDeadline = deadlines.schedule(() -> {
                    synchronized (cancelDeadlineLock) {
                        if (!cancelCompleted.get()) PinnedMysqlSocketFactory.abort(
                                managed.spec.properties().getProperty(PinnedMysqlSocketFactory.ROUTE_PROPERTY));
                    }
                }, 5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    statement.cancel();
                    return true;
                } finally {
                    synchronized (cancelDeadlineLock) { cancelCompleted.set(true); cancelDeadline.cancel(false); }
                }
            } catch (SQLException e) {
                log.warn("取消 MySQL 执行失败 executionId={} exceptionType={}", executionId, e.getClass().getSimpleName());
                return false;
            }
        }
    }

    @Override
    public SessionState state(String handleId) {
        ManagedConnection managed = requireHandle(handleId);
        return new SessionState(safeAutoCommit(managed.connection), transactionState(managed.connection),
                sessionTimeZone(managed.connection), isOpen(managed.connection));
    }

    @Override
    public void discardCancellation(String handleId, String executionId) {
        ManagedConnection managed = connections.get(handleId);
        if (managed == null) return;
        synchronized (managed.cancelLock) {
            if (!managed.statements.containsKey(executionId)) managed.cancelled.remove(executionId);
        }
    }

    @Override
    public void selectDatabase(String handleId, String database) {
        if (database == null || !database.matches("[A-Za-z_][A-Za-z0-9_$]{0,63}")) {
            throw new IllegalArgumentException("数据库标识符不合法");
        }
        ManagedConnection managed = requireHandle(handleId);
        try (Statement statement = managed.connection.createStatement()) {
            statement.execute("USE `" + database + "`");
            managed.currentDatabase = database;
        } catch (SQLException e) {
            throw new IllegalStateException(safeConnectionMessage(e), e);
        }
    }

    @Override
    public DbQueryResultEntity inspect(String handleId, String executionId,
            com.llf.ai.domain.db.model.valobj.DbInspectionRequest request, int maxRows, int timeoutSeconds) {
        var managed = requireHandle(handleId);
        boolean needsPerformanceSchema = switch (request.operation()) {
            case INSTANCE, PROCESSES, LOCKS -> true;
            default -> false;
        };
        if (needsPerformanceSchema && Boolean.FALSE.equals(managed.performanceSchemaEnabled)) {
            return DbQueryResultEntity.builder().capabilityState(com.llf.ai.domain.db.model.valobj.DbCapabilityState.DISABLED)
                    .safeError("目标实例未启用 Performance Schema")
                    .autoCommit(safeAutoCommit(managed.connection)).transactionState(transactionState(managed.connection))
                    .sessionReusable(isOpen(managed.connection)).build();
        }
        var query = MysqlInspectionQueries.create(request);
        var result = executeInternal(handleId, executionId, query.sql(), query.parameters(), maxRows, timeoutSeconds);
        if (request.operation() == com.llf.ai.domain.db.model.valobj.DbInspectionRequest.Operation.EXPLAIN) {
            result = MysqlExplainProjection.project(result, maxRows);
        }
        result.setCapabilityState(result.getSafeError() == null
                ? com.llf.ai.domain.db.model.valobj.DbCapabilityState.AVAILABLE
                : switch (result.getVendorCode() == null ? 0 : result.getVendorCode()) {
                    case 1044, 1045, 1142, 1143, 1227, 1370 -> com.llf.ai.domain.db.model.valobj.DbCapabilityState.PERMISSION_DENIED;
                    case 1064, 1109, 1146, 1235 -> com.llf.ai.domain.db.model.valobj.DbCapabilityState.UNSUPPORTED;
                    default -> com.llf.ai.domain.db.model.valobj.DbCapabilityState.TEMPORARILY_UNAVAILABLE;
                });
        return result;
    }

    @Override
    public TestConnectionResult test(DbConnectionEntity configuration) {
        OpenedSession opened = open(configuration, DbPhysicalConnectionQuota.Kind.TEST);
        try {
            var probe = probeCapabilities(opened.handleId());
            return new TestConnectionResult(opened.serverVersion(), opened.tlsEncrypted(),
                    opened.tlsIdentityVerified(), opened.sessionTimeZone(), probe.states());
        } finally {
            close(opened.handleId());
        }
    }

    private record CapabilityProbe(java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> states,
                                   boolean reusable) { }

    private CapabilityProbe probeCapabilities(String handleId) {
        var capabilities = new java.util.LinkedHashMap<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState>();
        boolean reusable = true;
        for (var operation : List.of(com.llf.ai.domain.db.model.valobj.DbInspectionRequest.Operation.INSTANCE,
                com.llf.ai.domain.db.model.valobj.DbInspectionRequest.Operation.PROCESSES,
                com.llf.ai.domain.db.model.valobj.DbInspectionRequest.Operation.LOCKS)) {
            var state = com.llf.ai.domain.db.model.valobj.DbCapabilityState.TEMPORARILY_UNAVAILABLE;
            if (reusable) {
                // 只使用固定诊断模板探测；每项两秒且不返回采样值，连接种类及配额保持不变。
                var result = inspect(handleId, "probe_" + UUID.randomUUID(),
                        new com.llf.ai.domain.db.model.valobj.DbInspectionRequest(operation, null, null, 1, null), 1, 2);
                state = result.getCapabilityState();
                reusable = result.isSessionReusable();
            }
            capabilities.put(operation.name(), state);
        }
        return new CapabilityProbe(java.util.Map.copyOf(capabilities), reusable);
    }

    private DbQueryResultEntity readRows(ResultSet resultSet, SQLWarning warning, int maxRows,
                                         long started, Connection connection) throws SQLException {
        if (resultSet == null) throw new SQLException("结果集不可用");
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<DbQueryResultEntity.DbColumnEntity> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(DbQueryResultEntity.DbColumnEntity.builder()
                    .ordinal(i)
                    .label(metadata.getColumnLabel(i))
                    .typeName(metadata.getColumnTypeName(i))
                    .jdbcType(metadata.getColumnType(i))
                    .nullable(metadata.isNullable(i) != ResultSetMetaData.columnNoNulls)
                    .precision(metadata.getPrecision(i))
                    .scale(metadata.getScale(i))
                    .build());
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> truncationReasons = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                truncationReasons.add("ROW_LIMIT");
                break;
            }
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                // 日期时间按数据库会话文本返回，避免 Timestamp.toString 使用 JVM 时区再次转换。
                Object cell = switch (metadata.getColumnType(i)) {
                    case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIMESTAMP -> resultSet.getString(i);
                    default -> resultSet.getObject(i);
                };
                String value;
                if (cell instanceof byte[] binary) {
                    value = binaryPreview(binary, maxCellBytes);
                    if (binary.length > maxCellBytes / 2) {
                        truncated = true;
                        if (!truncationReasons.contains("BINARY_LIMIT")) truncationReasons.add("BINARY_LIMIT");
                    }
                } else value = safeCell(cell);
                if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxCellBytes) {
                    value = truncateUtf8(value, maxCellBytes);
                    truncated = true;
                    if (!truncationReasons.contains("CELL_LIMIT")) truncationReasons.add("CELL_LIMIT");
                }
                if (value != null) bytes += value.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > maxResultBytes) {
                    row.add("[OMITTED]");
                    truncated = true;
                    if (!truncationReasons.contains("RESULT_LIMIT")) truncationReasons.add("RESULT_LIMIT");
                } else {
                    row.add(value);
                }
            }
            rows.add(row);
            if (bytes > maxResultBytes) break;
        }
        List<String> warnings = warnings(warning);
        long elapsed = Math.max(0, System.currentTimeMillis() - started);
        return DbQueryResultEntity.builder()
                .columns(columns).rows(rows).rowCount(rows.size())
                .truncated(truncated).truncationReasons(truncationReasons)
                .resultBytes(Math.min(bytes, maxResultBytes)).warnings(warnings)
                .executionMillis(elapsed).totalMillis(elapsed)
                .autoCommit(safeAutoCommit(connection)).transactionState(transactionState(connection))
                .sessionReusable(true).build();
    }

    private DbQueryResultEntity updateResult(long updateCount, SQLWarning warning,
                                             long started, Connection connection) {
        long elapsed = Math.max(0, System.currentTimeMillis() - started);
        return DbQueryResultEntity.builder()
                .affectedRows(updateCount < 0 ? null : (long) updateCount)
                .affectedRowsSemantics("CHANGED_ROWS")
                .warnings(warnings(warning)).executionMillis(elapsed).totalMillis(elapsed)
                .autoCommit(safeAutoCommit(connection)).transactionState(transactionState(connection))
                .sessionReusable(true).build();
    }

    private String safeCell(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        return String.valueOf(value);
    }

    static String binaryPreview(byte[] bytes, int maxBytes) {
        return HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, Math.max(0, maxBytes / 2)));
    }

    private String truncateUtf8(String value, int maxBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        int end = maxBytes;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private List<String> warnings(SQLWarning warning) {
        List<String> values = new ArrayList<>();
        SQLWarning current = warning;
        while (current != null && values.size() < 20) {
            values.add("数据库警告 SQLState=" + current.getSQLState() + " code=" + current.getErrorCode());
            current = current.getNextWarning();
        }
        return values;
    }

    private ManagedConnection requireHandle(String handleId) {
        ManagedConnection managed = connections.get(handleId);
        if (managed == null) throw new IllegalStateException("数据库物理连接不存在");
        return managed;
    }

    private String currentDatabase(ManagedConnection managed) {
        try {
            String catalog = managed.connection.getCatalog();
            return catalog == null || catalog.isBlank() ? managed.currentDatabase : catalog;
        } catch (SQLException e) {
            return managed.currentDatabase;
        }
    }

    private String sessionTimeZone(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT @@session.time_zone")) {
            return resultSet.next() ? limit(resultSet.getString(1), 64) : "UNKNOWN";
        } catch (SQLException e) {
            return "UNKNOWN";
        }
    }

    private boolean isTlsEncrypted(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW STATUS LIKE 'Ssl_cipher'")) {
            return resultSet.next() && resultSet.getString(2) != null
                    && !resultSet.getString(2).isBlank();
        } catch (SQLException e) {
            return false;
        }
    }

    private String transactionState(Connection connection) {
        try {
            com.mysql.cj.jdbc.JdbcConnection jdbc = connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);
            var state = jdbc.getSession().getServerSession();
            return state.inTransactionOnServer() ? "ACTIVE" : "IDLE";
        } catch (SQLException | RuntimeException e) {
            return "UNKNOWN";
        }
    }

    private boolean safeAutoCommit(Connection connection) {
        try {
            return connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class)
                    .getSession().getServerSession().isAutocommit();
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    private boolean isOpen(Connection connection) {
        try {
            return !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean isTlsIdentityVerified(DbConnectionEntity configuration) {
        return configuration.getSslMode() == SslModeEnum.VERIFY_IDENTITY;
    }

    private String safeSqlError(SQLException e) {
        return "数据库执行失败，请根据 SQLState 和错误码核查";
    }

    private String safeConnectionMessage(Throwable e) {
        return isTlsFailure(e) ? "数据库 TLS 证书验证失败，请检查 CA、有效期和证书身份"
                : "数据库连接失败，请检查网络、认证和 TLS 配置";
    }

    private boolean isTlsFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException) {
                return true;
            }
        }
        return false;
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void cleanupSpec(JdbcUrlFactory.ConnectionSpec spec) {
        if (spec != null) spec.cleanup();
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }

    private static final class ManagedConnection {
        private final String handleId;
        private final Connection connection;
        private final IDbTunnelPort.TunnelLease lease;
        private final Map<String, Statement> statements = new ConcurrentHashMap<>();
        private final Object cancelLock = new Object();
        private final java.util.Set<String> cancelled = ConcurrentHashMap.newKeySet();
        private final SslModeEnum sslMode;
        private final String tlsServerName;
        private final JdbcUrlFactory.ConnectionSpec spec;
        private volatile String currentDatabase;
        private Boolean performanceSchemaEnabled;

        private ManagedConnection(String handleId, Connection connection,
                                  IDbTunnelPort.TunnelLease lease,
                                  String currentDatabase, SslModeEnum sslMode,
                                  String tlsServerName, JdbcUrlFactory.ConnectionSpec spec) {
            this.handleId = handleId;
            this.connection = connection;
            this.lease = lease;
            this.currentDatabase = currentDatabase;
            this.sslMode = sslMode;
            this.tlsServerName = tlsServerName;
            this.spec = spec;
        }
    }
}
