package com.llf.ai.infrastructure.adapter.port;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.llf.ai.domain.ssh.adapter.port.ISshSessionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SshSessionPort implements ISshSessionPort {

    // 会话缓存：connectionId -> Session
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final JSch jsch = new JSch();

    @Override
    public boolean connect(String connectionId, String host, int port, String username,
                           String password, String privateKey) {
        // 如果已连接，先断开旧连接
        disconnect(connectionId);

        try {
            // 1. 创建会话
            Session session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(30000); // 30秒超时

            // 2. 设置认证方式
            if (privateKey != null && !privateKey.isEmpty()) {
                // 私钥认证
                jsch.addIdentity(connectionId, privateKey.getBytes(), null, null);
            } else if (password != null && !password.isEmpty()) {
                // 密码认证
                session.setPassword(password);
            } else {
                log.error("SSH连接失败：未提供认证信息 connectionId={}", connectionId);
                return false;
            }

            // 3. 建立连接
            session.connect();
            sessions.put(connectionId, session);
            log.info("SSH连接成功 connectionId={} host={}:{} user={}",
                    connectionId, host, port, username);
            return true;
        } catch (JSchException e) {
            log.error("SSH连接失败 connectionId={} host={}:{} error={}",
                    connectionId, host, port, e.getMessage());
            return false;
        }
    }

    @Override
    public void disconnect(String connectionId) {
        Session session = sessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH连接已断开 connectionId={}", connectionId);
        }
    }

    @Override
    public boolean isConnected(String connectionId) {
        Session session = sessions.get(connectionId);
        return session != null && session.isConnected();
    }
}
