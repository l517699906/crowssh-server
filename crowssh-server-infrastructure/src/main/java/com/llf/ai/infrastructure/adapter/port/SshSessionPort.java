package com.llf.ai.infrastructure.adapter.port;

import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    // 会话缓存：connectionId -> SSHClient
    private final ConcurrentHashMap<String, SSHClient> sessions = new ConcurrentHashMap<>();

    @Override
    public boolean connect(String connectionId, String host, int port, String username,
                           String password, String privateKey) {
        // 如果已连接，先断开旧连接
        disconnect(connectionId);

        if ((privateKey == null || privateKey.isEmpty())
                && (password == null || password.isEmpty())) {
            log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
            return false;
        }

        SSHClient sshClient = new SSHClient();
        try {
            // 忽略主机密钥检查
            sshClient.addHostKeyVerifier(new PromiscuousVerifier());
            sshClient.setConnectTimeout(30000);
            sshClient.setTimeout(30000);
            sshClient.connect(host, port);

            // 认证
            if (privateKey != null && !privateKey.isEmpty()) {
                OpenSSHKeyFile keyFile = new OpenSSHKeyFile();
                keyFile.init(privateKey, null, null);
                sshClient.authPublickey(username, keyFile);
            } else {
                sshClient.authPassword(username, password);
            }

            // 保存会话
            sessions.put(connectionId, sshClient);
            log.info("SSH连接成功 connectionId={} host={}:{} user={}",
                    connectionId, host, port, username);
            return true;
        } catch (IOException | RuntimeException e) {
            closeQuietly(sshClient);
            log.error("SSH连接失败 connectionId={} host={}:{} error={}",
                    connectionId, host, port, e.getMessage());
            return false;
        }
    }

    @Override
    public void disconnect(String connectionId) {
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

    private void closeQuietly(SSHClient sshClient) {
        try {
            sshClient.disconnect();
        } catch (IOException e) {
            log.warn("关闭SSH连接失败 error={}", e.getMessage());
        }
    }
}
