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
            List<String> reasons,
            /* UPDATE/DELETE 缺少 WHERE，或 WHERE 可被常量条件恒真满足 */
            boolean unboundedWrite,
            /* DROP/TRUNCATE 等不可逆的结构删除 */
            boolean destructiveDdl
    ) {
        public Analysis {
            references = references == null ? List.of() : List.copyOf(references);
            functions = functions == null ? List.of() : List.copyOf(functions);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        /** 不涉及写范围判定的分析结果。 */
        public Analysis(String sql, SqlStatementKind kind, List<ObjectReference> references,
                        boolean hasUnresolvedReferences, List<String> functions, boolean multiStatement,
                        boolean executableComment, boolean hasWildcardProjection, boolean hasSubquery,
                        boolean hasLockingClause, boolean hasFileAccess, boolean hasDynamicSql,
                        boolean hasUnsupportedAdmin, boolean requiresExplicitDatabase, List<String> reasons) {
            this(sql, kind, references, hasUnresolvedReferences, functions, multiStatement, executableComment,
                    hasWildcardProjection, hasSubquery, hasLockingClause, hasFileAccess, hasDynamicSql,
                    hasUnsupportedAdmin, requiresExplicitDatabase, reasons, false, false);
        }
    }

    record ObjectReference(String database, String table, String column) {
    }
}
