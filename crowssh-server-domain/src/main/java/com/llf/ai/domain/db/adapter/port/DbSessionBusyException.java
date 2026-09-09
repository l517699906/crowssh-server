package com.llf.ai.domain.db.adapter.port;

public class DbSessionBusyException extends IllegalStateException {
    public DbSessionBusyException(String message) {
        super(message);
    }
}
