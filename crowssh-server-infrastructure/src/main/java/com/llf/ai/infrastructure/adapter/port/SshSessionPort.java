package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import com.llf.ai.domain.ssh.adapter.port.HostKeyVerificationException;
import com.llf.ai.domain.ssh.model.entity.SshConnectionConfigEntity;
import com.llf.ai.domain.ssh.model.entity.SshConnectionEntity;
import com.llf.ai.infrastructure.security.PinnedHostKeyVerifier;
import com.llf.ai.infrastructure.security.SshOutboundPolicy;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    // 会话缓存：connectionId -> SSHClient
    private final ConcurrentHashMap<String, SSHClient> sessions = new ConcurrentHashMap<>();
    private final SshOutboundPolicy outboundPolicy;

    public SshSessionPort(SshOutboundPolicy outboundPolicy) {
        this.outboundPolicy = outboundPolicy;
    }

    @Override
    public synchronized boolean connect(SshConnectionEntity connection, SshConnectionConfigEntity config) {
        String connectionId = connection.getConnectionId();
        if (isConnected(connectionId)) {
            log.debug("复用已建立的 SSH 连接 connectionId={}", connectionId);
            return true;
        }

        disconnect(connectionId);

        try {
            SSHClient sshClient = establishConnection(connection, config);
            sessions.put(connectionId, sshClient);
            log.info("SSH连接成功 connectionId={} host={}:{} user={} connectTimeout={}s keepalive={}s",
                    connectionId, connection.getHost(), connection.getPort(), connection.getUsername(),
                    config.getConnectTimeout(), config.getKeepaliveInterval());
            return true;
        } catch (HostKeyVerificationException e) {
            log.warn("SSH 主机密钥校验拒绝 connectionId={} host={}:{} fingerprint={} changed={}",
                    connectionId, connection.getHost(), connection.getPort(),
                    e.getFingerprint(), e.isChanged());
            throw e;
        } catch (com.llf.ai.domain.ssh.adapter.port.SshTargetBlockedException e) {
            log.warn("SSH 出站策略拒绝 connectionId={} host={}:{}", connectionId,
                    connection.getHost(), connection.getPort());
            throw e;
        } catch (IOException | RuntimeException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}",
                    connectionId, connection.getHost(), connection.getPort(), e.getMessage());
            return false;
        }
    }

    @Override
    public void testConnection(SshConnectionEntity connection, SshConnectionConfigEntity config) {
        SSHClient sshClient = null;
        try {
            sshClient = establishConnection(connection, config);
            log.info("SSH连接测试成功 host={}:{} user={} connectTimeout={}s keepalive={}s",
                    connection.getHost(), connection.getPort(), connection.getUsername(),
                    config.getConnectTimeout(), config.getKeepaliveInterval());
        } catch (HostKeyVerificationException e) {
            log.warn("SSH 主机密钥校验拒绝 host={}:{} fingerprint={} changed={}",
                    connection.getHost(), connection.getPort(), e.getFingerprint(), e.isChanged());
            throw e;
        } catch (com.llf.ai.domain.ssh.adapter.port.SshTargetBlockedException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("SSH连接测试失败 host={}:{} user={} error={}",
                    connection.getHost(), connection.getPort(), connection.getUsername(), e.getMessage());
            throw new IllegalStateException("SSH连接测试失败: " + errorMessage(e), e);
        } finally {
            closeQuietly(sshClient);
        }
    }

    @Override
    public synchronized void disconnect(String connectionId) {
        SSHClient sshClient = sessions.remove(connectionId);
        if (sshClient != null) {
            closeQuietly(sshClient);
            log.info("SSH连接已断开 connectionId={}", connectionId);
        }
    }

    @Override
    public boolean isConnected(String connectionId) {
        SSHClient sshClient = sessions.get(connectionId);
        return sshClient != null && sshClient.isConnected() && sshClient.isAuthenticated();
    }

    /**
     * 为终端适配器创建 SSHJ Session。
     * 仅限基础设施层内部使用，避免将 SSHJ 类型暴露到领域端口。
     */
    Session openSession(String connectionId) throws IOException {
        SSHClient sshClient = sessions.get(connectionId);
        if (sshClient == null || !sshClient.isConnected() || !sshClient.isAuthenticated()) {
            throw new IllegalStateException("SSH会话不可用 connectionId=" + connectionId);
        }
        return sshClient.startSession();
    }

    SFTPClient openSftpClient(String connectionId) throws IOException {
        SSHClient sshClient = sessions.get(connectionId);
        if (sshClient == null || !sshClient.isConnected() || !sshClient.isAuthenticated()) {
            throw new IllegalStateException("SSH会话不可用 connectionId=" + connectionId);
        }
        return sshClient.newSFTPClient();
    }

    private SSHClient establishConnection(
            SshConnectionEntity connection, SshConnectionConfigEntity config) throws IOException {
        String password = connection.getPassword();
        String privateKey = connection.getPrivateKey();
        if ((privateKey == null || privateKey.isEmpty())
                && (password == null || password.isEmpty())) {
            throw new IllegalArgumentException("未提供认证信息");
        }

        config.withDefaults();
        config.validate();

        SSHClient sshClient = new SSHClient();
        PinnedHostKeyVerifier pinnedHostKeyVerifier = new PinnedHostKeyVerifier(config.getKnownHosts());
        try {
            sshClient.addHostKeyVerifier(pinnedHostKeyVerifier);
            sshClient.setConnectTimeout(config.getConnectTimeout() * 1000);
            if (config.getKeepaliveInterval() > 0) {
                sshClient.getConnection().getKeepAlive()
                        .setKeepAliveInterval(config.getKeepaliveInterval());
            }
            // 使用同一次解析得到的地址，避免 hostname 在校验后被重新解析。
            sshClient.connect(outboundPolicy.resolveAllowedAddress(connection.getHost()), connection.getPort());

            if (privateKey != null && !privateKey.isEmpty()) {
                OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
                keyFile.init(privateKey, null, null);
                sshClient.authPublickey(connection.getUsername(), keyFile);
            } else {
                sshClient.authPassword(connection.getUsername(), password);
            }
            return sshClient;
        } catch (IOException | RuntimeException e) {
            closeQuietly(sshClient);
            HostKeyVerificationException rejection = pinnedHostKeyVerifier.getRejection();
            if (rejection != null) {
                throw rejection;
            }
            throw e;
        }
    }

    private String errorMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private void closeQuietly(SSHClient sshClient) {
        if (sshClient == null) {
            return;
        }
        try {
            sshClient.disconnect();
        } catch (IOException e) {
            log.warn("关闭SSH连接失败 error={}", e.getMessage());
        }
    }
}
