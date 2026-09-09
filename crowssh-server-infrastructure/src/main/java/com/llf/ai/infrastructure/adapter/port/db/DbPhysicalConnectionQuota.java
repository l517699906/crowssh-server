package com.llf.ai.infrastructure.adapter.port.db;

import com.llf.ai.domain.db.config.DbSessionGovernanceProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** 名额从拨号前持有到 socket 关闭，包含失败中的握手和驱动取消连接。 */
@Component
public final class DbPhysicalConnectionQuota {
    public enum Kind { DATA, TEST, CONTROL }

    private final DbSessionGovernanceProperties properties;
    private final Map<Kind, Integer> totals = new EnumMap<>(Kind.class);
    private final Map<String, Map<Kind, Integer>> owners = new HashMap<>();

    public DbPhysicalConnectionQuota(DbSessionGovernanceProperties properties) {
        this.properties = properties;
    }

    public synchronized Lease acquire(String ownerId, Kind kind) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("设备身份不能为空");
        var settings = properties.getSession();
        int ownerLimit = switch (kind) {
            case DATA -> settings.getMaxDataConnectionsPerOwner();
            case TEST -> settings.getMaxTestConnectionsPerOwner();
            case CONTROL -> settings.getMaxControlConnectionsPerOwner();
        };
        int globalLimit = switch (kind) {
            case DATA -> settings.getMaxDataConnectionsGlobal();
            case TEST -> settings.getMaxTestConnectionsGlobal();
            case CONTROL -> settings.getMaxControlConnectionsGlobal();
        };
        Map<Kind, Integer> counts = owners.get(ownerId);
        int ownerCount = counts == null ? 0 : counts.getOrDefault(kind, 0);
        int total = totals.getOrDefault(kind, 0);
        if (ownerCount >= ownerLimit || total >= globalLimit) {
            throw new IllegalStateException("数据库物理连接配额已用尽：" + kind);
        }
        owners.computeIfAbsent(ownerId, ignored -> new EnumMap<>(Kind.class)).put(kind, ownerCount + 1);
        totals.put(kind, total + 1);
        return new Lease(ownerId, kind);
    }

    public final class Lease implements AutoCloseable {
        private final String ownerId;
        private final Kind kind;
        private boolean closed;

        private Lease(String ownerId, Kind kind) { this.ownerId = ownerId; this.kind = kind; }

        @Override
        public void close() {
            synchronized (DbPhysicalConnectionQuota.this) {
                if (closed) return;
                closed = true;
                totals.compute(kind, (ignored, count) -> count - 1);
                Map<Kind, Integer> counts = owners.get(ownerId);
                counts.compute(kind, (ignored, count) -> count == 1 ? null : count - 1);
                if (counts.isEmpty()) owners.remove(ownerId);
            }
        }
    }
}
