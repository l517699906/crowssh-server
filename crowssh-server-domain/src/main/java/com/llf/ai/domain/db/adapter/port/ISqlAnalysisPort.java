package com.llf.ai.domain.db.adapter.port;

import com.llf.ai.domain.db.model.valobj.SqlStatementKind;

import java.util.List;

public interface ISqlAnalysisPort {
    Analysis analyze(String sql);

    record Analysis(
            String sql,
            SqlStatementKind kind,
            List<ObjectReference> references,
            boolean hasUnresolvedReferences,
            List<String> functions,
            boolean multiStatement,
            boolean executableComment,
            boolean hasWildcardProjection,
            boolean hasSubquery,
            boolean hasLockingClause,
            boolean hasFileAccess,
            boolean hasDynamicSql,
            boolean hasUnsupportedAdmin,
            boolean requiresExplicitDatabase,
            List<String> reasons
    ) {
        public Analysis {
            references = references == null ? List.of() : List.copyOf(references);
            functions = functions == null ? List.of() : List.copyOf(functions);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    record ObjectReference(String database, String table, String column) {
    }
}
