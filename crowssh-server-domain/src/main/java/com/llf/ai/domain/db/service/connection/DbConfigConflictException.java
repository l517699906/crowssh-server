package com.llf.ai.domain.db.service.connection;

public class DbConfigConflictException extends IllegalStateException {
    public DbConfigConflictException(String message) {
        super(message);
    }
}
