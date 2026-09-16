package com.llf.ai.infrastructure.adapter.port.db;

import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.valobj.SslModeEnum;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Collection;
import java.util.Locale;
import java.util.Properties;

/** 只由服务端生成单目标 Connector/J URL 和固定安全属性。 */
@org.springframework.stereotype.Component
public class JdbcUrlFactory {
    private final DbPhysicalConnectionQuota quota;

    public JdbcUrlFactory(DbPhysicalConnectionQuota quota) { this.quota = quota; }

    public ConnectionSpec create(DbConnectionEntity connection, String actualHost, int actualPort,
                                 DbPhysicalConnectionQuota.Kind kind) {
        String database = connection.getDefaultDatabase();
        connection.validate();
        String identity = connection.getTlsServerName() == null ? connection.getHost() : connection.getTlsServerName();
        String host = formatHost(identity);
        String url = "jdbc:mysql://" + host + ":" + actualPort
                + (database == null || database.isBlank() ? "/" : "/" + database);
        Properties properties = new Properties();
        properties.setProperty("user", connection.getUsername());
        if (connection.getPassword() != null) properties.setProperty("password", connection.getPassword());
        properties.setProperty("sslMode", connection.getSslMode().name());
        properties.setProperty("allowLoadLocalInfile", "false");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("allowUrlInLocalInfile", "false");
        properties.setProperty("allowPublicKeyRetrieval", "false");
        properties.setProperty("autoReconnect", "false");
        properties.setProperty("useConfigs", "");
        properties.setProperty("connectTimeout", String.valueOf(connection.getConnectTimeout() * 1000));
        properties.setProperty("socketTimeout", String.valueOf(connection.getQueryTimeout() * 1000));
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("socketFactory", PinnedMysqlSocketFactory.class.getName());
        properties.setProperty("enableQueryTimeouts", "false");
        // 禁止驱动在关闭流式结果时追加 SET，避免覆盖原查询警告；期限由 socket 和执行截止时间负责。
        properties.setProperty("netTimeoutForStreamingResults", "0");
        properties.setProperty("useAffectedRows", "true");
        properties.setProperty("useServerPrepStmts", "false");
        properties.setProperty("maxAllowedPacket", "8388608");
        properties.setProperty("profileSQL", "false");
        properties.setProperty("dumpQueriesOnException", "false");
        properties.setProperty("includeThreadDumpInDeadlockExceptions", "false");
        properties.setProperty("includeInnodbStatusInDeadlockExceptions", "false");
        properties.setProperty("characterEncoding", "UTF-8");
        properties.setProperty("connectionTimeZone", "UTC");
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        Path trustStore = null;
        if (connection.getCaCertificatePem() != null && !connection.getCaCertificatePem().isBlank()) {
            trustStore = createTrustStore(connection.getCaCertificatePem());
            properties.setProperty("trustCertificateKeyStoreUrl", trustStore.toUri().toString());
            properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
            properties.setProperty("trustCertificateKeyStorePassword", trustStorePassword(trustStore));
        }
        String routeId = PinnedMysqlSocketFactory.register(actualHost, actualPort,
                connection.getUserId(), quota, kind);
        properties.setProperty(PinnedMysqlSocketFactory.ROUTE_PROPERTY, routeId);
        return new ConnectionSpec(url, properties, trustStore);
    }

    private Path createTrustStore(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates = factory.generateCertificates(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (certificates.isEmpty()) throw new IllegalArgumentException("CA 证书链为空");
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                keyStore.setCertificateEntry("ca-" + index++, certificate);
            }
            Path path = Files.createTempFile("crowssh-db-ca-", ".p12");
            char[] password = temporaryPassword(path);
            try (var output = Files.newOutputStream(path)) {
                keyStore.store(output, password);
            }
            return path;
        } catch (Exception e) {
            throw new IllegalArgumentException("CA 公共证书链不可解析", e);
        }
    }

    private String trustStorePassword(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private char[] temporaryPassword(Path path) {
        return trustStorePassword(path).toCharArray();
    }

    private String formatHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    public record ConnectionSpec(String url, Properties properties, Path temporaryTrustStore) {
        public void cleanup() {
            PinnedMysqlSocketFactory.unregister(properties.getProperty(PinnedMysqlSocketFactory.ROUTE_PROPERTY));
            properties.remove("password");
            if (temporaryTrustStore == null) return;
            try {
                Files.deleteIfExists(temporaryTrustStore);
            } catch (IOException ignored) {
                // public CA material is not a credential; best effort cleanup is sufficient.
            }
        }
    }
}
