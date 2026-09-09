package com.llf.ai.domain.db.model.valobj;

import java.util.List;

public record SqlPolicyDecision(
        SqlPolicyAction action,
        String riskLevel,
        List<String> reasons
) {
    public SqlPolicyDecision {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public boolean allowed() {
        return action == SqlPolicyAction.ALLOW;
    }
}
