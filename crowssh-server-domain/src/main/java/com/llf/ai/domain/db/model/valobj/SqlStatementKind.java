package com.llf.ai.domain.db.model.valobj;

public enum SqlStatementKind {
    QUERY,
    DML,
    DDL,
    ADMIN,
    SESSION,
    TRANSACTION,
    MULTI,
    UNKNOWN
}
