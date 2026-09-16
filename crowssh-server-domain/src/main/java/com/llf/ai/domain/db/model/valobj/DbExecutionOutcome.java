package com.llf.ai.domain.db.model.valobj;

public enum DbExecutionOutcome {
    SUCCEEDED,
    FAILED,
    REJECTED,
    EXPIRED,
    CONTEXT_CHANGED,
    CANCELLED,
    TIMED_OUT,
    OUTCOME_UNKNOWN
}
