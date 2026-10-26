package com.llf.ai.domain.db.service.sql;

import com.llf.ai.domain.db.adapter.port.ISqlAnalysisPort;
import com.llf.ai.domain.db.model.valobj.SqlStatementKind;

import java.util.ArrayList;
import java.util.List;

/** 只做风险解释，不负责决定 SQL 是否可执行。 */
public class DangerousSqlDetector {
    public Risk detect(ISqlAnalysisPort.Analysis analysis) {
        List<String> reasons = new ArrayList<>();
        String risk = "LOW";
        if (analysis.hasFileAccess()) {
            risk = "HIGH";
            reasons.add("文件读写可能越过数据库数据边界");
        }
        if (analysis.hasLockingClause()) {
            risk = "HIGH";
            reasons.add("语句可能持有或创建数据库锁");
        }
        // 影响范围只取自 AST 分析结果，不在这里对 SQL 文本做词法猜测。
        if (analysis.unboundedWrite()) {
            risk = "HIGH";
            reasons.add("修改语句缺少可靠的影响范围限制");
        }
        if (analysis.destructiveDdl() || analysis.kind() == SqlStatementKind.DDL
                || analysis.kind() == SqlStatementKind.ADMIN) {
            risk = "HIGH";
            reasons.add("语句可能产生不可逆或隐式提交影响");
        } else if (analysis.kind() == SqlStatementKind.DML) {
            if (!"HIGH".equals(risk)) risk = "MEDIUM";
            reasons.add("写入或管理操作的实际影响需执行后确认");
        }
        if (analysis.reasons() != null) reasons.addAll(analysis.reasons());
        return new Risk(risk, reasons);
    }

    public record Risk(String level, List<String> reasons) {
        public Risk {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
