package com.llf.ai.infrastructure.adapter.port.db;

import com.llf.ai.domain.db.adapter.port.DbTargetBlockedException;
import com.llf.ai.domain.db.adapter.port.IDbTunnelPort;
import com.llf.ai.infrastructure.adapter.port.SshSessionPort;
import com.llf.ai.infrastructure.security.DbOutboundPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SshLocalTunnelPort implements IDbTunnelPort {
    private final SshSessionPort sshSessionPort;
    private final DbOutboundPolicy outboundPolicy;
    private final Map<String, String> allowedTargets;
    private record Target(String ownerId, String connectionId, String ip, int port) { }
    private static final class SharedForward {
        final SshSessionPort.LocalForward forward;
        int references;
        SharedForward(SshSessionPort.LocalForward forward) { this.forward = forward; }
    }
    private final Map<Target, SharedForward> shared = new ConcurrentHashMap<>();
    private final Map<String, Target> leases = new ConcurrentHashMap<>();

    public SshLocalTunnelPort(
            SshSessionPort sshSessionPort,
            DbOutboundPolicy outboundPolicy,
            @Value("${crowssh.db.egress.allowed-tunnel-targets:}") String configuredTargets) {
        this.sshSessionPort = sshSessionPort;
        this.outboundPolicy = outboundPolicy;
        this.allowedTargets = parseTargets(configuredTargets);
    }

    @Override
    public synchronized TunnelLease acquire(String ownerId, String sshConnectionId,
                               String destinationHost, int destinationPort) {
        if (sshConnectionId == null || sshConnectionId.isBlank()) {
            throw new IllegalArgumentException("SSH 隧道连接 ID 不能为空");
        }
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("设备身份不能为空");
        if (destinationPort < 1 || destinationPort > 65535) {
            throw new DbTargetBlockedException("SSH 隧道目的端口不合法");
        }
        String ip = outboundPolicy.requireTunnelIpLiteral(destinationHost);
        String key = sshConnectionId + "|" + ip + ":" + destinationPort;
        if (allowedTargets.isEmpty() || !allowedTargets.containsKey(key)) {
            throw new DbTargetBlockedException("SSH 隧道目的地址未被服务端允许");
        }
        Target target = new Target(ownerId, sshConnectionId, ip, destinationPort);
        SharedForward entry = shared.get(target);
        if (entry != null && !sshSessionPort.isLocalForwardActive(entry.forward.leaseId())) {
            throw new IllegalStateException("SSH 隧道租约已失效，请关闭旧工作台后重新打开");
        }
        if (entry == null) {
            entry = new SharedForward(sshSessionPort.openLocalForward(sshConnectionId, ip, destinationPort));
            shared.put(target, entry);
        }
        String leaseId = "db_tunnel_" + java.util.UUID.randomUUID();
        entry.references++;
        leases.put(leaseId, target);
        return new TunnelLease(leaseId, "127.0.0.1", entry.forward.localPort());
    }

    @Override
    public boolean isActive(String leaseId) {
        if (leaseId == null) return false;
        Target target = leases.get(leaseId);
        SharedForward entry = target == null ? null : shared.get(target);
        return entry != null && sshSessionPort.isLocalForwardActive(entry.forward.leaseId())
                && leases.get(leaseId) == target;
    }

    @Override
    public void release(String leaseId) {
        String forwardId = null;
        synchronized (this) {
            Target target = leases.remove(leaseId);
            if (target == null) return;
            SharedForward entry = shared.get(target);
            if (--entry.references == 0) {
                shared.remove(target);
                forwardId = entry.forward.leaseId();
            }
        }
        if (forwardId != null) sshSessionPort.closeLocalForward(forwardId);
    }

    private Map<String, String> parseTargets(String configured) {
        Map<String, String> result = new java.util.HashMap<>();
        if (configured == null || configured.isBlank()) return result;
        Arrays.stream(configured.split(";"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    String[] parts = value.split(",", 3);
                    if (parts.length != 3) throw new IllegalStateException("数据库隧道允许目标格式应为 sshConnectionId,ip,port");
                    String ip = outboundPolicy.requireTunnelIpLiteral(parts[1]);
                    int port = Integer.parseInt(parts[2]);
                    result.put(parts[0] + "|" + ip + ":" + port, value);
                });
        return Map.copyOf(result);
    }
}
