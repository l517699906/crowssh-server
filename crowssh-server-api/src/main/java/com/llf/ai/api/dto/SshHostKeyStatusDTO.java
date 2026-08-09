package com.llf.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSH 主机密钥确认信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SshHostKeyStatusDTO {

    private String fingerprint;
    private String algorithm;
    private Boolean changed;
}
