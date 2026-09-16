package com.llf.ai.domain.db.model.entity;

import com.llf.ai.domain.db.model.valobj.DbExecutionOutcome;
import com.llf.ai.domain.db.model.valobj.DbExecutionSourceEnum;
import com.llf.ai.domain.db.model.valobj.DbExecutionState;
import com.llf.ai.domain.db.model.valobj.DbOperationKind;
import com.llf.ai.domain.db.model.valobj.SqlStatementKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DbExecutionEntity {
    private String executionId;
    private String ownerId;
    private String dbSessionId;
    private long sessionGeneration;
    private DbExecutionSourceEnum source;
    private DbOperationKind operationKind;
    private String agentSessionId;
    private String turnId;
    private String sqlHash;
    private String sql;
    private String targetDatabase;
    private long configVersion;
    private long targetContextVersion;
    private long consoleContextVersion;
    private SqlStatementKind statementKind;
    private String riskLevel;
    private String policyReason;
    private DbExecutionState state;
    private DbExecutionOutcome outcome;
    private boolean cancelRequested;
    private boolean resultAvailable;
    private LocalDateTime expiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private DbQueryResultEntity result;

    public boolean isTerminal() {
        return state == DbExecutionState.FINISHED;
    }
}
