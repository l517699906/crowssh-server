package com.llf.ai.domain.db.model.entity;

import com.llf.ai.domain.db.model.valobj.DbLaneStatusEnum;
import com.llf.ai.domain.db.model.valobj.DbSessionStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DbSessionEntity {
    private String dbSessionId;
    private long sessionGeneration;
    private String ownerId;
    private String connectionId;
    private String tunnelSshConnectionId;
    private long configVersion;
    private String currentDatabase;
    private long targetContextVersion;
    private long consoleContextVersion;
    private String consoleHandleId;
    private String aiHandleId;
    @Builder.Default
    private DbLaneStatusEnum consoleLaneStatus = DbLaneStatusEnum.NOT_OPEN;
    @Builder.Default
    private DbLaneStatusEnum aiLaneStatus = DbLaneStatusEnum.NOT_OPEN;
    @Builder.Default
    private DbSessionStatusEnum lifecycleStatus = DbSessionStatusEnum.OPENING;
    private boolean autoCommit;
    private String transactionState;
    private String sessionTimeZone;
    private String serverVersion;
    private boolean tlsEncrypted;
    private boolean tlsIdentityVerified;
    @Builder.Default
    private java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> capabilityStates = java.util.Map.of();
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;

    public boolean isOpen() {
        return lifecycleStatus == DbSessionStatusEnum.OPENING
                || lifecycleStatus == DbSessionStatusEnum.READY;
    }
}
