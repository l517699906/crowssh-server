package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DbSessionOpenResponseDTO {
    private String dbSessionId;
    private long sessionGeneration;
    private String connectionId;
    private long configVersion;
    private String currentDatabase;
    private long targetContextVersion;
    private String lifecycleStatus;
    private String consoleLaneStatus;
    private String aiLaneStatus;
    private boolean autoCommit;
    private String transactionState;
    private String sessionTimeZone;
    private String serverVersion;
    private boolean tlsEncrypted;
    private boolean tlsIdentityVerified;
    private java.util.Map<String, String> capabilityStates;
}
