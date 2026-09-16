package com.llf.ai.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class DbTestConnectionResponseDTO {
    private String serverVersion;
    private List<String> capabilities;
    private java.util.Map<String, String> capabilityStates;
    private boolean tlsEncrypted;
    private boolean tlsIdentityVerified;
    private String sessionTimeZone;
}
