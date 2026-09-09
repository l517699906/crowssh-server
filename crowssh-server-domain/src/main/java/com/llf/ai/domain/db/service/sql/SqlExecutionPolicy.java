package com.llf.ai.domain.db.service.sql;

import com.llf.ai.domain.db.adapter.port.ISqlAnalysisPort;
import com.llf.ai.domain.db.model.valobj.AiDataModeEnum;
import com.llf.ai.domain.db.model.valobj.DbAllowedColumn;
import com.llf.ai.domain.db.model.valobj.DbExecutionSourceEnum;
import com.llf.ai.domain.db.model.valobj.SqlPolicyAction;
import com.llf.ai.domain.db.model.valobj.SqlPolicyDecision;
import com.llf.ai.domain.db.model.valobj.SqlStatementKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** DB SQL 的唯一策略决策入口；控制台与 AI 仅在来源上有不同授权边界。 */
public class SqlExecutionPolicy {
    private static final List<String> SAFE_FUNCTIONS = List.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "LOWER", "UPPER",
            "LENGTH", "COALESCE", "NULLIF", "ROUND", "CAST"
    );

    private final DangerousSqlDetector dangerousSqlDetector;

    public SqlExecutionPolicy() {
        this(new DangerousSqlDetector());
    }

    public SqlExecutionPolicy(DangerousSqlDetector dangerousSqlDetector) {
        this.dangerousSqlDetector = dangerousSqlDetector;
    }

    public SqlPolicyDecision decide(
            ISqlAnalysisPort.Analysis analysis,
            DbExecutionSourceEnum source,
            AiDataModeEnum aiDataMode,
            List<DbAllowedColumn> allowedColumns,
            String currentDatabase
    ) {
        DangerousSqlDetector.Risk risk = dangerousSqlDetector.detect(analysis);
        List<String> reasons = new ArrayList<>(risk.reasons());
        if (source == null) return deny("执行来源缺失", risk.level(), reasons);
        if (analysis.sql() == null || analysis.sql().isBlank()) {
            return deny("SQL 不能为空", risk.level(), reasons);
        }
        if (analysis.kind() == SqlStatementKind.MULTI || analysis.kind() == SqlStatementKind.UNKNOWN
                || analysis.multiStatement() || analysis.executableComment()
                || analysis.hasFileAccess() || analysis.hasDynamicSql()
                || analysis.hasUnsupportedAdmin() || analysis.hasLockingClause()
                ) {
            reasons.add("语法或副作用不在一期支持范围");
            return deny("一期不支持该 SQL", risk.level(), reasons);
        }
        for (String function : analysis.functions()) {
            if (!SAFE_FUNCTIONS.contains(function.toUpperCase(Locale.ROOT))) {
                return deny("语句包含未验证的函数", risk.level(), reasons);
            }
        }
        if (source == DbExecutionSourceEnum.AI) {
            if (analysis.kind() == SqlStatementKind.SESSION
                    || analysis.kind() == SqlStatementKind.TRANSACTION) {
                return deny("AI 不允许修改会话或事务状态", risk.level(), reasons);
            }
            if (analysis.kind() == SqlStatementKind.QUERY) {
                if (analysis.hasUnresolvedReferences()) {
                    return deny("查询包含无法绑定到完整数据库列的引用", risk.level(), reasons);
                }
                if (aiDataMode != AiDataModeEnum.ALLOWLIST) {
                    if (analysis.references().isEmpty()) return allow(risk.level(), reasons);
                    return deny("当前连接仅开放结构与诊断信息", risk.level(), reasons);
                }
                if (analysis.hasWildcardProjection()) {
                    return deny("AI 查询必须使用明确列清单", risk.level(), reasons);
                }
                if (analysis.references().isEmpty()) {
                    return allow(risk.level(), reasons);
                }
                if (!allColumnsAllowed(analysis.references(), allowedColumns, currentDatabase)) {
                    return deny("查询引用了未授权的数据库列", risk.level(), reasons);
                }
                for (String function : analysis.functions()) {
                    if (!SAFE_FUNCTIONS.contains(function.toUpperCase())) {
                        return deny("查询包含未验证的函数", risk.level(), reasons);
                    }
                }
                return allow(risk.level(), reasons);
            }
            if (analysis.kind() == SqlStatementKind.DML || analysis.kind() == SqlStatementKind.DDL
                    || analysis.kind() == SqlStatementKind.ADMIN) {
                if (analysis.hasSubquery()) return deny("含读取子查询的写操作尚未完成来源授权", risk.level(), reasons);
                if (analysis.requiresExplicitDatabase()) {
                    return deny("AI 写操作必须显式限定数据库", risk.level(), reasons);
                }
                reasons.add("精确 SQL 必须由当前设备单次审批");
                return new SqlPolicyDecision(SqlPolicyAction.REQUIRE_APPROVAL, risk.level(), reasons);
            }
            return deny("AI 不支持该 SQL 类型", risk.level(), reasons);
        }

        return allow(risk.level(), reasons);
    }

    private boolean allColumnsAllowed(List<ISqlAnalysisPort.ObjectReference> references,
                                      List<DbAllowedColumn> allowedColumns, String currentDatabase) {
        if (allowedColumns == null || allowedColumns.isEmpty()) return false;
        return references.stream().allMatch(reference -> (reference.database() != null || currentDatabase != null)
                && reference.table() != null && reference.column() != null
                && allowedColumns.stream().anyMatch(rule -> rule.matches(
                reference.database() == null ? currentDatabase : reference.database(), reference.table(), reference.column())));
    }

    private SqlPolicyDecision allow(String risk, List<String> reasons) {
        return new SqlPolicyDecision(SqlPolicyAction.ALLOW, risk, reasons);
    }

    private SqlPolicyDecision deny(String reason, String risk, List<String> reasons) {
        List<String> all = new ArrayList<>();
        all.add(reason);
        all.addAll(reasons);
        return new SqlPolicyDecision(SqlPolicyAction.DENY, risk, all);
    }
}
