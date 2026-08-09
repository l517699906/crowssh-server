package com.llf.ai.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SSH 凭据 AES-256-GCM 加解密组件。
 *
 * <p>新密文包含版本和 key id；旧无前缀密文只通过显式配置的兼容密钥读取。</p>
 */
@Slf4j
@Component
public class PasswordEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final String PREFIX = VERSION + ":";
    private static final String INSECURE_DEVELOPMENT_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8;

    private final SecureRandom secureRandom = new SecureRandom();
    private final KeyMaterial primaryKey;
    private final Map<String, SecretKeySpec> versionedKeys;
    private final List<SecretKeySpec> legacyCandidates;

    @Autowired
    public PasswordEncryptor(
            @Value("${crowssh.crypto.primary-key-id:primary}") String primaryKeyId,
            @Value("${crowssh.crypto.primary-key:}") String primaryKeyBase64,
            @Value("${crowssh.crypto.previous-keys:}") String previousKeys,
            @Value("${crowssh.crypto.legacy-keys:}") String legacyKeys,
            @Value("${crowssh.crypto.allow-insecure-development-key:false}") boolean allowDevelopmentKey,
            Environment environment
    ) {
        this(primaryKeyId, primaryKeyBase64, previousKeys, legacyKeys,
                allowDevelopmentKey,
                environment.acceptsProfiles(Profiles.of("prod", "production", "release")));
    }

    PasswordEncryptor(
            String primaryKeyId,
            String primaryKeyBase64,
            String previousKeys,
            String legacyKeys,
            boolean allowDevelopmentKey,
            boolean production
    ) {
        if (production && allowDevelopmentKey) {
            throw new IllegalStateException("生产环境禁止启用开发凭据密钥");
        }
        String effectivePrimaryKey = primaryKeyBase64 == null ? "" : primaryKeyBase64.trim();
        if (effectivePrimaryKey.isEmpty()) {
            if (!allowDevelopmentKey) {
                throw new IllegalStateException("缺少 crowssh.crypto.primary-key，服务拒绝启动");
            }
            effectivePrimaryKey = INSECURE_DEVELOPMENT_KEY;
            log.warn("已显式启用不安全的开发凭据密钥，禁止用于生产环境");
        }

        String normalizedPrimaryId = requireKeyId(primaryKeyId);
        SecretKeySpec primarySpec = decodeBase64Key(effectivePrimaryKey, "primary-key");
        this.primaryKey = new KeyMaterial(normalizedPrimaryId, primarySpec);

        Map<String, SecretKeySpec> configuredKeys = new LinkedHashMap<>();
        configuredKeys.put(normalizedPrimaryId, primarySpec);
        parseBase64KeyEntries(previousKeys, "previous-keys").forEach((keyId, keySpec) -> {
            if (configuredKeys.putIfAbsent(keyId, keySpec) != null) {
                throw new IllegalStateException("凭据密钥 key id 重复: " + keyId);
            }
        });
        this.versionedKeys = Map.copyOf(configuredKeys);

        List<SecretKeySpec> legacy = new ArrayList<>(configuredKeys.values());
        legacy.addAll(parseLegacyKeyEntries(legacyKeys));
        this.legacyCandidates = List.copyOf(legacy);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        String payload = encryptPayload(
                plaintext,
                primaryKey.keySpec(),
                iv,
                aad(primaryKey.keyId()));
        return PREFIX + primaryKey.keyId() + ":" + payload;
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        if (ciphertext.startsWith(PREFIX)) {
            String[] parts = ciphertext.split(":", 3);
            if (parts.length != 3 || !KEY_ID_PATTERN.matcher(parts[1]).matches()) {
                throw new IllegalStateException("凭据密文格式无效");
            }
            SecretKeySpec keySpec = versionedKeys.get(parts[1]);
            if (keySpec == null) {
                throw new IllegalStateException("凭据密文引用了未配置的 key id: " + parts[1]);
            }
            return decryptPayload(parts[2], keySpec, aad(parts[1]));
        }

        for (SecretKeySpec candidate : legacyCandidates) {
            try {
                return decryptPayload(ciphertext, candidate, null);
            } catch (IllegalStateException ignored) {
                // 旧密文没有 key id，只能依次尝试显式配置的兼容密钥。
            }
        }
        throw new IllegalStateException("凭据解密失败，未找到可用的兼容密钥");
    }

    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String payload = value;
        if (value.startsWith(PREFIX)) {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !KEY_ID_PATTERN.matcher(parts[1]).matches()) {
                return false;
            }
            payload = parts[2];
        }
        try {
            return Base64.getDecoder().decode(payload).length
                    >= GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String encryptPayload(
            String plaintext,
            SecretKeySpec keySpec,
            byte[] iv,
            byte[] aad
    ) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            if (aad != null) cipher.updateAAD(aad);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    private String decryptPayload(String payload, SecretKeySpec keySpec, byte[] aad) {
        try {
            byte[] combined = Base64.getDecoder().decode(payload);
            if (combined.length < GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
                throw new IllegalStateException("凭据密文长度无效");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            if (aad != null) cipher.updateAAD(aad);
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("凭据解密失败", e);
        }
    }

    private Map<String, SecretKeySpec> parseBase64KeyEntries(String entries, String propertyName) {
        Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
        for (KeyEntry entry : parseEntries(entries, propertyName)) {
            String keyId = requireKeyId(entry.keyId());
            if (parsed.putIfAbsent(keyId, decodeBase64Key(entry.value(), propertyName)) != null) {
                throw new IllegalStateException(propertyName + " 中 key id 重复: " + keyId);
            }
        }
        return parsed;
    }

    private List<SecretKeySpec> parseLegacyKeyEntries(String entries) {
        return parseEntries(entries, "legacy-keys").stream()
                .map(entry -> new SecretKeySpec(legacyPadOrTrim(entry.value()), "AES"))
                .toList();
    }

    private List<KeyEntry> parseEntries(String entries, String propertyName) {
        if (entries == null || entries.isBlank()) {
            return List.of();
        }
        List<KeyEntry> parsed = new ArrayList<>();
        for (String rawEntry : entries.split(";")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) continue;
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException(propertyName + " 格式必须为 keyId=value;keyId=value");
            }
            parsed.add(new KeyEntry(
                    entry.substring(0, separator).trim(),
                    entry.substring(separator + 1).trim()));
        }
        return parsed;
    }

    private SecretKeySpec decodeBase64Key(String value, String propertyName) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(value);
            if (keyBytes.length != KEY_LENGTH_BYTES) {
                throw new IllegalStateException(propertyName + " 必须是 Base64 编码的 32 字节密钥");
            }
            return new SecretKeySpec(keyBytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " 必须是有效 Base64", e);
        }
    }

    private byte[] legacyPadOrTrim(String rawKey) {
        byte[] source = rawKey.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[KEY_LENGTH_BYTES];
        System.arraycopy(source, 0, key, 0, Math.min(source.length, key.length));
        return key;
    }

    private String requireKeyId(String keyId) {
        String normalized = keyId == null ? "" : keyId.trim();
        if (!KEY_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("凭据密钥 key id 仅允许字母、数字、点、下划线和连字符");
        }
        return normalized;
    }

    private byte[] aad(String keyId) {
        return (PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private record KeyMaterial(String keyId, SecretKeySpec keySpec) {
    }

    private record KeyEntry(String keyId, String value) {
    }
}
