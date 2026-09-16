package com.llf.ai.domain.db.adapter.port;

/** 工作台准入配额耗尽，不用于表示建连或TLS失败。 */
public final class DbSessionQuotaException extends IllegalStateException {
    public DbSessionQuotaException(String message) { super(message); }
}
