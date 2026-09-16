package com.llf.ai.infrastructure.security;

import com.llf.ai.domain.db.adapter.port.DbTargetBlockedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 数据库直连地址与隧道最终目的地址的独立出站策略。 */
@Component
public class DbOutboundPolicy {
    private final List<String> allowedPrivateHosts;

    public DbOutboundPolicy(
            @Value("${crowssh.db.egress.allowed-private-hosts:}") String allowedPrivateHosts) {
        this.allowedPrivateHosts = allowedPrivateHosts == null || allowedPrivateHosts.isBlank()
                ? List.of() : Arrays.stream(allowedPrivateHosts.split(","))
                .map(this::normalize).filter(value -> !value.isBlank()).toList();
    }

    public InetAddress resolveDirectAddress(String host) throws UnknownHostException {
        String normalized = normalize(host);
        InetAddress[] addresses = InetAddress.getAllByName(normalized);
        if (addresses.length == 0) throw new UnknownHostException(normalized);
        boolean hostAllowed = allowedPrivateHosts.contains(normalized);
        for (InetAddress address : addresses) {
            if (isRestricted(address) && !hostAllowed
                    && !allowedPrivateHosts.contains(normalize(address.getHostAddress()))) {
                throw new DbTargetBlockedException("数据库直连目标地址被出站策略阻止");
            }
        }
        return addresses[0];
    }

    public String requireTunnelIpLiteral(String host) {
        String normalized = normalize(host);
        if (!isIpLiteral(normalized)) {
            throw new DbTargetBlockedException("SSH 隧道目的地址一期必须是 IP 字面量");
        }
        try {
            InetAddress address = InetAddress.getByName(normalized);
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            throw new DbTargetBlockedException("SSH 隧道目的 IP 不合法");
        }
    }

    public boolean isIpLiteral(String host) {
        return host != null && com.google.common.net.InetAddresses.isInetAddress(host);
    }

    private String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("数据库主机地址不能为空");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.isBlank() || normalized.length() > 255
                || normalized.chars().anyMatch(Character::isWhitespace)
                || normalized.indexOf('/') >= 0 || normalized.indexOf('?') >= 0
                || normalized.indexOf('&') >= 0 || normalized.indexOf('#') >= 0
                || normalized.indexOf('=') >= 0 || normalized.indexOf('@') >= 0) {
            throw new IllegalArgumentException("数据库主机地址不合法");
        }
        return normalized;
    }

    private boolean isRestricted(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;
        if (address instanceof Inet4Address) {
            byte[] b = address.getAddress();
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first == 100 && second >= 64 && second <= 127 || first == 0 || first == 10 || first == 127 || first == 169 && second == 254
                    || first == 172 && second >= 16 && second <= 31
                    || first == 192 && second == 168
                    || first >= 224;
        }
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            if (isMapped(b)) {
                try {
                    return isRestricted(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
            return (b[0] & 0xfe) == 0xfc || (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80;
        }
        return true;
    }

    private boolean isMapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}
