package com.llf.ai.infrastructure.security;

import com.llf.ai.domain.ssh.adapter.port.SshTargetBlockedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * SSH 出站目标校验。
 *
 * <p>解析和连接必须使用同一个已解析地址，避免校验 hostname 后再次解析产生 DNS
 * rebinding 窗口。私网地址只有在显式主机白名单中才允许。</p>
 */
@Component
public class SshOutboundPolicy {

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedPrivateHosts;
    private final HostResolver resolver;

    @Autowired
    public SshOutboundPolicy(
            @Value("${crowssh.ssh.egress.allowed-private-hosts:}") String allowedPrivateHosts
    ) {
        this(allowedPrivateHosts, InetAddress::getAllByName);
    }

    SshOutboundPolicy(String allowedPrivateHosts, HostResolver resolver) {
        this.allowedPrivateHosts = parseAllowlist(allowedPrivateHosts);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * 返回本次校验使用的固定地址；调用方不得再按原 hostname 建连。
     */
    public InetAddress resolveAllowedAddress(String host) throws UnknownHostException {
        String normalizedHost = normalizeHost(host);
        InetAddress[] addresses = resolver.resolve(normalizedHost);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(normalizedHost);
        }

        boolean hostAllowed = matchesAllowlist(normalizedHost);
        for (InetAddress address : addresses) {
            if (address == null) {
                throw new UnknownHostException(normalizedHost);
            }
            if (isRestricted(address)
                    && !hostAllowed
                    && !matchesAllowlist(address.getHostAddress())) {
                throw new SshTargetBlockedException(
                        "SSH 目标地址被出站策略阻止: " + normalizedHost);
            }
        }
        return addresses[0];
    }

    static boolean isRestricted(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet4Address) {
            return isRestrictedIpv4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // IPv4-mapped IPv6 addresses must use the IPv4 policy as well.
            if (isIpv4Mapped(bytes)) {
                return isRestrictedIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // Unique-local (fc00::/7), documentation (2001:db8::/32),
            // and deprecated site-local (fec0::/10).
            return (first & 0xfe) == 0xfc
                    || (first == 0x20 && second == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8)
                    || (first == 0xfe && (second & 0xc0) == 0xc0);
        }
        return true;
    }

    private static boolean isRestrictedIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        return first == 0
                || first == 10
                || first == 100 && second >= 64 && second <= 127 // CGNAT
                || first == 127
                || first == 169 && second == 254
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 0 && (third == 0 || third == 2)
                || first == 192 && second == 168
                || first == 198 && (second == 18 || second == 19 || second == 51)
                || first == 203 && second == 0 && third == 113
                || first >= 224;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private List<String> parseAllowlist(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty() && !"*".equals(item))
                .map(this::normalizeHost)
                .toList();
    }

    private boolean matchesAllowlist(String value) {
        String normalized = normalizeHost(value);
        for (String pattern : allowedPrivateHosts) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (normalized.endsWith(suffix)
                        && normalized.length() > suffix.length()) {
                    return true;
                }
            } else if (pattern.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeHost(String value) {
        if (value == null) {
            throw new IllegalArgumentException("SSH 主机地址不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.length() > 255) {
            throw new IllegalArgumentException("SSH 主机地址无效");
        }
        return normalized;
    }
}
