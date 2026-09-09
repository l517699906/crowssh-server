package com.llf.ai.domain.db.adapter.port;

/** TLS 握手/证书验证失败，与普通网络和账号认证失败分开。 */
public final class DbTlsVerificationException extends IllegalStateException {
    public DbTlsVerificationException(String message, Throwable cause) { super(message, cause); }
}
