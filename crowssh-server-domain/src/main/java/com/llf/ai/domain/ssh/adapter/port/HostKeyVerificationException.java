package com.llf.ai.domain.ssh.adapter.port;

/**
 * SSH 主机密钥未信任或发生变化。
 */
public class HostKeyVerificationException extends RuntimeException {

    private final String fingerprint;
    private final String algorithm;
    private final boolean changed;

    public HostKeyVerificationException(
            String message,
            String fingerprint,
            String algorithm,
            boolean changed
    ) {
        super(message);
        this.fingerprint = fingerprint;
        this.algorithm = algorithm;
        this.changed = changed;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public boolean isChanged() {
        return changed;
    }
}
