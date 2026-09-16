package com.llf.ai.domain.db.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crowssh.db")
public class DbSessionGovernanceProperties {
    private Session session = new Session();
    private Query query = new Query();
    private Execution execution = new Execution();
    private Approval approval = new Approval();
    private Ai ai = new Ai();
    private Egress egress = new Egress();

    @Data
    public static class Session {
        private long idleTimeoutMillis = 1_800_000L;
        private long reapIntervalMs = 60_000L;
        private int maxSessionsPerOwner = 10;
        private int maxSessionsGlobal = 50;
        private int maxDataConnectionsPerOwner = 20;
        private int maxDataConnectionsGlobal = 100;
        private int maxControlConnectionsPerOwner = 2;
        private int maxControlConnectionsGlobal = 10;
        private int maxTestConnectionsPerOwner = 2;
        private int maxTestConnectionsGlobal = 10;
        private int cleanupTimeoutSeconds = 10;
    }

    @Data
    public static class Query {
        private int defaultMaxRows = 1000;
        private int hardMaxRows = 5000;
        private int defaultQueryTimeoutSeconds = 60;
        private int hardQueryTimeoutSeconds = 120;
        private int hardTotalTimeoutSeconds = 150;
        private int hardConnectTimeoutSeconds = 30;
        private int maxSqlBytes = 65_536;
        private int maxCellBytes = 65_536;
        private int maxResultBytes = 8 * 1024 * 1024;
        private int maxDriverPacketBytes = 8 * 1024 * 1024;
    }

    @Data
    public static class Execution {
        private int preparedTtlSeconds = 120;
        private int resultTtlSeconds = 900;
        private int maxRecordsPerOwner = 1000;
        private int maxRecordsGlobal = 5000;
        private long maxRetainedResultBytesPerOwner = 16 * 1024 * 1024L;
        private long maxRetainedResultBytesGlobal = 128 * 1024 * 1024L;
    }

    @Data
    public static class Approval {
        private int ttlSeconds = 300;
        private int maxPendingPerOwner = 10;
    }

    @Data
    public static class Ai {
        private int maxResultRows = 50;
        private int maxCellBytes = 2048;
        private int maxResultBytes = 65_536;
        private int maxResultTokens = 6000;
    }

    @Data
    public static class Egress {
        private String allowedPrivateHosts = "";
    }
}
