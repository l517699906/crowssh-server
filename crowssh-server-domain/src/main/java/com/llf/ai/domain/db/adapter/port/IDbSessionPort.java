package com.llf.ai.domain.db.adapter.port;

import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;

public interface IDbSessionPort {

    OpenedSession open(DbConnectionEntity connection);

    /** 初始化独立 AI 连接的固定会话语义；不支持该能力的适配器拒绝 AI 执行。 */
    default void initializeAiSession(String handleId) {
        throw new IllegalStateException("数据库适配器未实现 AI 会话基线");
    }

    void close(String handleId);

    /** 只检查外部传输租约的本地状态，不发 SQL 或等待网络；无外部租约的适配器默认有效。 */
    default boolean isTransportActive(String handleId) { return true; }

    DbQueryResultEntity execute(String handleId, String executionId, String sql,
                                int maxRows, int queryTimeoutSeconds, boolean autoCommit);

    boolean cancel(String handleId, String executionId);

    /** 领域确认尚未进入驱动即结束，释放提前到达的取消标记。 */
    default void discardCancellation(String handleId, String executionId) { }

    SessionState state(String handleId);

    void selectDatabase(String handleId, String database);

    TestConnectionResult test(DbConnectionEntity connection);

    record OpenedSession(
            String handleId,
            String currentDatabase,
            boolean autoCommit,
            String transactionState,
            String sessionTimeZone,
            String serverVersion,
            boolean tlsEncrypted,
            boolean tlsIdentityVerified,
            java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> capabilityStates
    ) {
        public OpenedSession {
            capabilityStates = capabilityStates == null ? java.util.Map.of() : java.util.Map.copyOf(capabilityStates);
        }
        public OpenedSession(String handleId, String currentDatabase, boolean autoCommit, String transactionState,
                             String sessionTimeZone, String serverVersion, boolean tlsEncrypted, boolean tlsIdentityVerified) {
            this(handleId, currentDatabase, autoCommit, transactionState, sessionTimeZone, serverVersion,
                    tlsEncrypted, tlsIdentityVerified, java.util.Map.of());
        }
    }

    record SessionState(boolean autoCommit, String transactionState, String sessionTimeZone,
                        boolean reusable) {
    }

    record TestConnectionResult(String serverVersion, boolean tlsEncrypted,
                                boolean tlsIdentityVerified, String sessionTimeZone,
                                java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> capabilityStates) {
        public TestConnectionResult {
            capabilityStates = capabilityStates == null ? java.util.Map.of() : java.util.Map.copyOf(capabilityStates);
        }
        public TestConnectionResult(String serverVersion, boolean tlsEncrypted,
                                    boolean tlsIdentityVerified, String sessionTimeZone) {
            this(serverVersion, tlsEncrypted, tlsIdentityVerified, sessionTimeZone, java.util.Map.of());
        }
    }
}
