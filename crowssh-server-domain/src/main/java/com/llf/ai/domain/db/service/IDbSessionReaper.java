package com.llf.ai.domain.db.service;

@FunctionalInterface
public interface IDbSessionReaper {
    int reapIdleSessions();
}
