package com.llf.ai.infrastructure.security;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加解密工具
 * 使用 AES-256-GCM 对称加密，密钥从环境变量或默认值读取
 *
 * @author llf
 */
@Slf4j
public class PasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    /** 默认密钥（生产环境务必通过环境变量 CROWSSH_SECRET_KEY 覆盖） */
    private static final String DEFAULT_SECRET_KEY = "CrowSSH2026SecretKey!!";

    private final SecretKeySpec keySpec;

    public PasswordEncryptor() {
        this(null);
    }

    public PasswordEncryptor(String secretKey) {
        String key = secretKey;
        if (key == null || key.isBlank()) {
            key = System.getenv("CROWSSH_SECRET_KEY");
        }
        if (key == null || key.isBlank()) {
            key = DEFAULT_SECRET_KEY;
            log.warn("未配置 CROWSSH_SECRET_KEY 环境变量，使用默认密钥，生产环境请务必配置！");
        }
        // 确保密钥长度为 32 字节（AES-256）
        byte[] keyBytes = padOrTrim(key.getBytes(StandardCharsets.UTF_8), 32);
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文，返回 Base64 编码的密文（包含 IV 前缀）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // IV + 密文拼接后 Base64
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("密码加密失败", e);
            throw new RuntimeException("密码加密失败", e);
        }
    }

    /**
     * 解密 Base64 编码的密文，返回明文
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("密码解密失败", e);
            throw new RuntimeException("密码解密失败", e);
        }
    }

    /**
     * 判断字符串是否是加密后的格式（Base64 且长度 > IV_LENGTH）
     */
    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length > GCM_IV_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] padOrTrim(byte[] bytes, int length) {
        byte[] result = new byte[length];
        System.arraycopy(bytes, 0, result, 0, Math.min(bytes.length, length));
        return result;
    }
}
