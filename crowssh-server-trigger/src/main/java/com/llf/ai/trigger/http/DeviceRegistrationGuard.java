package com.llf.ai.trigger.http;

import com.llf.ai.types.enums.ResponseCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备身份注册的 HTTP 层滥用门禁。
 *
 * <p>计数器是单实例固定窗口，生产多副本时应将该状态迁移到 Redis 或网关。</p>
 */
@Component
public class DeviceRegistrationGuard {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private final boolean enabled;
    private final String inviteCode;
    private final int maxPerIp;
    private final long windowMillis;
    private final int maxActivePrincipals;
    private final Clock clock;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AtomicLong attempts = new AtomicLong();

    @Autowired
    public DeviceRegistrationGuard(
            @Value("${crowssh.auth.registration.enabled:true}") boolean enabled,
            @Value("${crowssh.auth.registration.invite-code:}") String inviteCode,
            @Value("${crowssh.auth.registration.max-per-ip:5}") int maxPerIp,
            @Value("${crowssh.auth.registration.window-seconds:3600}") long windowSeconds,
            @Value("${crowssh.auth.registration.max-active-principals:1000}") int maxActivePrincipals
    ) {
        this(enabled, inviteCode, maxPerIp, windowSeconds, maxActivePrincipals, Clock.systemUTC());
    }

    DeviceRegistrationGuard(
            boolean enabled,
            String inviteCode,
            int maxPerIp,
            long windowSeconds,
            int maxActivePrincipals,
            Clock clock
    ) {
        if (maxPerIp <= 0 || windowSeconds <= 0 || maxActivePrincipals <= 0) {
            throw new IllegalArgumentException("设备注册限流和配额必须大于 0");
        }
        this.enabled = enabled;
        this.inviteCode = inviteCode == null ? "" : inviteCode.trim();
        this.maxPerIp = maxPerIp;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000L);
        this.maxActivePrincipals = maxActivePrincipals;
        this.clock = clock;
    }

    public int maxActivePrincipals() {
        return maxActivePrincipals;
    }

    public void check(String remoteAddress, String suppliedInviteCode) {
        if (!enabled) {
            throw new DeviceRegistrationRejectedException(
                    ResponseCode.DEVICE_REGISTRATION_DISABLED,
                    HttpStatus.FORBIDDEN,
                    0);
        }

        String address = normalizeAddress(remoteAddress);
        long now = clock.millis();
        cleanupExpired(now);
        if (!counters.containsKey(address) && counters.size() >= MAX_TRACKED_ADDRESSES) {
            throw new DeviceRegistrationRejectedException(
                    ResponseCode.DEVICE_REGISTRATION_RATE_LIMITED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    retryAfterSeconds(now, now + windowMillis));
        }

        AtomicBoolean allowed = new AtomicBoolean(true);
        AtomicLong retryAt = new AtomicLong(now + windowMillis);
        counters.compute(address, (key, previous) -> {
            if (previous == null || now - previous.startedAt() >= windowMillis) {
                return new WindowCounter(now, 1);
            }
            if (previous.count() >= maxPerIp) {
                allowed.set(false);
                retryAt.set(previous.startedAt() + windowMillis);
                return previous;
            }
            return new WindowCounter(previous.startedAt(), previous.count() + 1);
        });
        attempts.incrementAndGet();

        if (!allowed.get()) {
            throw new DeviceRegistrationRejectedException(
                    ResponseCode.DEVICE_REGISTRATION_RATE_LIMITED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    retryAfterSeconds(now, retryAt.get()));
        }

        if (!inviteCode.isEmpty() && !constantTimeEquals(inviteCode, suppliedInviteCode)) {
            throw new DeviceRegistrationRejectedException(
                    ResponseCode.DEVICE_REGISTRATION_INVITE_INVALID,
                    HttpStatus.FORBIDDEN,
                    0);
        }
    }

    private void cleanupExpired(long now) {
        if ((attempts.get() & 0xFF) != 0) {
            return;
        }
        counters.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= windowMillis);
    }

    private long retryAfterSeconds(long now, long retryAt) {
        return Math.max(1, (retryAt - now + 999) / 1000);
    }

    private String normalizeAddress(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress.trim();
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] suppliedBytes = (supplied == null ? "" : supplied.trim()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, suppliedBytes);
    }

    private record WindowCounter(long startedAt, int count) {
    }
}

class DeviceRegistrationRejectedException extends RuntimeException {

    private final ResponseCode responseCode;
    private final HttpStatus status;
    private final long retryAfterSeconds;

    DeviceRegistrationRejectedException(ResponseCode responseCode, HttpStatus status, long retryAfterSeconds) {
        super(responseCode.getInfo());
        this.responseCode = responseCode;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    ResponseCode getResponseCode() {
        return responseCode;
    }

    HttpStatus getStatus() {
        return status;
    }

    long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
