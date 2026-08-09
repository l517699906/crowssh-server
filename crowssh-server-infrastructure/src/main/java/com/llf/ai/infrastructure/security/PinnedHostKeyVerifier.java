package com.llf.ai.infrastructure.security;

import com.llf.ai.domain.ssh.adapter.port.HostKeyVerificationException;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * 仅接受已显式固定的 SHA-256 主机密钥指纹。
 */
public final class PinnedHostKeyVerifier implements HostKeyVerifier {

    private final String expectedFingerprint;
    private volatile HostKeyVerificationException rejection;

    public PinnedHostKeyVerifier(String expectedFingerprint) {
        this.expectedFingerprint = normalize(expectedFingerprint);
        if (this.expectedFingerprint != null) {
            FingerprintVerifier.getInstance(this.expectedFingerprint);
        }
    }

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
        String presentedFingerprint = sha256Fingerprint(key);
        if (expectedFingerprint == null) {
            rejection = new HostKeyVerificationException(
                    "SSH 主机密钥尚未信任，请核对指纹后确认",
                    presentedFingerprint,
                    key.getAlgorithm(),
                    false
            );
            return false;
        }

        boolean matches = FingerprintVerifier.getInstance(expectedFingerprint)
                .verify(hostname, port, key);
        if (!matches) {
            rejection = new HostKeyVerificationException(
                    "SSH 主机密钥已变化，连接已拒绝",
                    presentedFingerprint,
                    key.getAlgorithm(),
                    true
            );
        }
        return matches;
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
        return List.of();
    }

    public HostKeyVerificationException getRejection() {
        return rejection;
    }

    static String sha256Fingerprint(PublicKey key) {
        try {
            MessageDigest digest = SecurityUtils.getMessageDigest("SHA-256");
            digest.update(new Buffer.PlainBuffer().putPublicKey(key).getCompactData());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256 主机指纹", e);
        }
    }

    private static String normalize(String fingerprint) {
        return fingerprint == null || fingerprint.isBlank() ? null : fingerprint.trim();
    }
}
