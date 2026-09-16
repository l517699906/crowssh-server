package com.llf.ai.infrastructure.adapter.port.db;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlKillStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.llf.ai.domain.db.adapter.port.ISqlAnalysisPort;
import com.llf.ai.domain.db.model.valobj.SqlStatementKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** MySQL AST 分析；解析失败和未经验证的节点均拒绝，不回退到词法放行。 */
@Component
public class MysqlSqlAnalysisAdapter implements ISqlAnalysisPort {
    private static final Set<String> EXPRESSIONS = Set.of(
            "SQLIdentifierExpr", "SQLPropertyExpr", "SQLIntegerExpr", "SQLNumberExpr",
            "SQLCharExpr", "SQLNCharExpr", "SQLNullExpr", "SQLBooleanExpr", "SQLHexExpr",
            "SQLBinaryOpExpr", "SQLUnaryExpr", "SQLBetweenExpr", "SQLInListExpr",
            "SQLCaseExpr", "Item", "SQLSelectItem", "SQLMethodInvokeExpr", "SQLAggregateExpr", "SQLCastExpr",
            "SQLAllColumnExpr", "SQLQueryExpr", "SQLInSubQueryExpr", "SQLExistsExpr");
    private static final Set<String> STATEMENTS = Set.of(
            "SQLSelectStatement", "MySqlInsertStatement", "MySqlUpdateStatement",
            "MySqlDeleteStatement", "SQLReplaceStatement", "MySqlCreateTableStatement",
            "SQLAlterTableStatement", "SQLDropTableStatement", "SQLCreateIndexStatement",
            "SQLDropIndexStatement", "SQLTruncateStatement", "SQLCreateDatabaseStatement",
            "SQLDropDatabaseStatement", "MySqlKillStatement", "SQLUseStatement",
            "SQLSetStatement", "SQLStartTransactionStatement", "SQLBeginStatement",
            "SQLCommitStatement", "SQLRollbackStatement");

    @Override
    public Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) return rejected(sql, false, false, "SQL 不能为空");
        // 这里只拒绝特殊注释；是否为合法、单条和受支持的 SQL 完全由 AST 决定。
        if (sql.contains("/*!") || sql.contains("/*+") || sql.contains("/*M!")) {
            return rejected(sql, false, true, "可执行注释及优化器提示一期拒绝");
        }
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, DbType.mysql);
            if (statements.size() != 1) return rejected(sql, true, false, "一次只允许一条 SQL");
            SQLStatement statement = statements.get(0);
            SqlStatementKind kind = classify(statement);
            if (kind == SqlStatementKind.UNKNOWN) return rejected(sql, false, false, "该 SQL 类型尚未支持");
            Inspection inspection = new Inspection(statement);
            statement.accept(inspection);
            if (kind == SqlStatementKind.SESSION || kind == SqlStatementKind.TRANSACTION) {
                inspection.unsupported |= !supportedControl(statement);
            }
            MySqlSchemaStatVisitor schema = new MySqlSchemaStatVisitor();
            statement.accept(schema);
            List<ObjectReference> references = new ArrayList<>();
            boolean unresolved = false;
            for (var column : schema.getColumns()) {
                String table = SQLUtils.normalize(column.getTable());
                String name = SQLUtils.normalize(column.getName());
                String[] parts = table == null ? new String[0] : table.split("\\.", -1);
                if (parts.length == 2) references.add(new ObjectReference(parts[0], parts[1], name));
                else if (parts.length == 1 && !parts[0].equals("UNKNOWN")) {
                    references.add(new ObjectReference(null, parts[0], name));
                } else unresolved = true;
            }
            // COUNT(*) 和常量投影仍然读取表；不能把没有列引用当成不读取业务数据。
            for (var table : schema.getTables().keySet()) {
                String[] parts = SQLUtils.normalize(table.getName()).split("\\.", -1);
                String db = parts.length == 2 ? parts[0] : null;
                String name = parts[parts.length - 1];
                if (references.stream().noneMatch(ref -> name.equals(ref.table()))) {
                    references.add(new ObjectReference(db, name, "*"));
                }
            }
            boolean writes = kind == SqlStatementKind.DML || kind == SqlStatementKind.DDL;
            List<String> reasons = new ArrayList<>();
            if (inspection.unsupported) reasons.add("存在未经验证的 AST 节点或会话副作用");
            if (inspection.fileAccess) reasons.add("禁止文件读写及 SELECT INTO");
            if (inspection.locking) reasons.add("锁定查询需要受控事务支持");
            return new Analysis(sql, kind, references, unresolved, inspection.functions,
                    false, false, inspection.wildcard, inspection.selectCount > 1 || (writes && inspection.selectCount > 0),
                    inspection.locking, inspection.fileAccess, inspection.dynamic,
                    inspection.unsupported, writes && inspection.unqualifiedTable, reasons);
        } catch (RuntimeException | StackOverflowError e) {
            // 解析器异常可含 SQL 原文，不进入日志或返回值。
            return rejected(sql, false, false, "SQL 无法完整解析或超出解析复杂度限制");
        }
    }

    private SqlStatementKind classify(SQLStatement statement) {
        if (!STATEMENTS.contains(statement.getClass().getSimpleName())) return SqlStatementKind.UNKNOWN;
        if (statement instanceof SQLSelectStatement) return SqlStatementKind.QUERY;
        if (statement instanceof SQLInsertStatement || statement instanceof SQLUpdateStatement
                || statement instanceof SQLDeleteStatement || statement instanceof SQLReplaceStatement) return SqlStatementKind.DML;
        if (statement instanceof SQLUseStatement || statement instanceof SQLSetStatement) return SqlStatementKind.SESSION;
        if (statement instanceof SQLStartTransactionStatement || statement instanceof SQLBeginStatement
                || statement instanceof SQLCommitStatement || statement instanceof SQLRollbackStatement) return SqlStatementKind.TRANSACTION;
        if (statement instanceof MySqlKillStatement kill) {
            return kill.getType() == MySqlKillStatement.Type.QUERY
                    && kill.getThreadId() instanceof SQLIntegerExpr ? SqlStatementKind.ADMIN : SqlStatementKind.UNKNOWN;
        }
        return SqlStatementKind.DDL;
    }

    private boolean supportedControl(SQLStatement statement) {
        if (statement instanceof SQLUseStatement use) return use.getDatabase() instanceof SQLIdentifierExpr;
        if (statement instanceof SQLSetStatement set) {
            if (set.getOption() != null || set.getItems().size() != 1) return false;
            SQLAssignItem item = set.getItems().get(0);
            String name;
            if (item.getTarget() instanceof SQLIdentifierExpr identifier) name = identifier.getName();
            else if (item.getTarget() instanceof SQLVariantRefExpr variable && !variable.isGlobal()
                    && !variable.getName().startsWith("@")) name = variable.getName();
            else return false;
            name = name.toLowerCase(Locale.ROOT);
            if (name.equals("autocommit") && item.getValue() instanceof SQLIntegerExpr number) {
                return number.getNumber().toString().equals("0") || number.getNumber().toString().equals("1");
            }
            return name.equals("time_zone") && item.getValue() instanceof SQLCharExpr text
                    && text.getText().matches("(?:SYSTEM|[+-](?:0[0-9]|1[0-4]):[0-5][0-9])");
        }
        String normalized = SQLUtils.toMySqlString(statement).trim().replace(";", "");
        return Set.of("BEGIN", "START TRANSACTION", "COMMIT", "ROLLBACK").contains(normalized);
    }

    private Analysis rejected(String sql, boolean multi, boolean comment, String reason) {
        return new Analysis(sql == null ? "" : sql, multi ? SqlStatementKind.MULTI : SqlStatementKind.UNKNOWN,
                List.of(), true, List.of(), multi, comment, false, false, false, false,
                false, false, false, List.of(reason));
    }

    private static final class Inspection extends MySqlASTVisitorAdapter {
        private final SQLStatement root;
        private final List<String> functions = new ArrayList<>();
        private boolean unsupported, fileAccess, locking, dynamic, wildcard, unqualifiedTable;
        private int selectCount;

        private Inspection(SQLStatement root) { this.root = root; }

        @Override
        public void preVisit(SQLObject node) {
            String name = node.getClass().getSimpleName();
            boolean setTarget = root instanceof SQLSetStatement && node.getParent() instanceof SQLAssignItem assignment
                    && assignment.getTarget() == node;
            if (node instanceof SQLStatement && node != root) unsupported = true;
            if (node instanceof com.alibaba.druid.sql.ast.SQLExpr
                    && !EXPRESSIONS.contains(name)
                    && !(node instanceof SQLAssignItem && root instanceof SQLSetStatement) && !setTarget) unsupported = true;
            if (node instanceof SQLVariantRefExpr && !setTarget) unsupported = true;
            if (node instanceof SQLMethodInvokeExpr method) {
                functions.add((method.getOwner() == null ? "" : "QUALIFIED.") + method.getMethodName().toUpperCase(Locale.ROOT));
            }
            if (node instanceof SQLSelectQueryBlock query) {
                selectCount++;
                fileAccess |= query.getInto() != null;
                locking |= query.isForUpdate() || query.isForShare();
                if (query instanceof MySqlSelectQueryBlock mysql) {
                    locking |= mysql.isLockInShareMode();
                    dynamic |= mysql.getProcedureName() != null;
                }
            }
            if (node instanceof SQLAllColumnExpr && !(node.getParent() instanceof SQLAggregateExpr)) wildcard = true;
            if (node instanceof SQLPropertyExpr property && "*".equals(property.getName())) wildcard = true;
            if (node instanceof SQLExprTableSource table) unqualifiedTable |= table.getSchema() == null;
            if (name.contains("OutFile") || name.contains("Outfile")) fileAccess = true;
            if (node instanceof SQLCreateTableStatement create) {
                unsupported |= create.getLike() != null || create.getSelect() != null;
                for (SQLAssignItem option : create.getTableOptions()) {
                    String key = option.getTarget().toString().toUpperCase(Locale.ROOT);
                    if (!Set.of("ENGINE", "CHARSET", "CHARACTER SET", "COLLATE", "AUTO_INCREMENT", "COMMENT").contains(key)) unsupported = true;
                    if (key.equals("ENGINE") && !option.getValue().toString().equalsIgnoreCase("InnoDB")) unsupported = true;
                }
            }
        }
    }
}
