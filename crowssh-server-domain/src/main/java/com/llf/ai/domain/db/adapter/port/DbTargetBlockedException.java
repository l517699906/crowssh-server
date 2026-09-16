package com.llf.ai.domain.db.adapter.port;

public class DbTargetBlockedException extends IllegalArgumentException {
    public DbTargetBlockedException(String message) {
        super(message);
    }
}
