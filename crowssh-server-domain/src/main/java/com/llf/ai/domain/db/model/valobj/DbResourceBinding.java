package com.llf.ai.domain.db.model.valobj;

public record DbResourceBinding(
        String ownerId,
        String agentSessionId,
        String turnId,
        String dbConnectionId,
        String dbSessionId,
        long sessionGeneration,
        long configVersion,
        String targetDatabase,
        long targetContextVersion
) {
    public DbResourceBinding {
        require(ownerId, "ownerId");
        require(dbConnectionId, "dbConnectionId");
        require(dbSessionId, "dbSessionId");
        if (sessionGeneration < 1 || configVersion < 1 || targetContextVersion < 0) {
            throw new IllegalArgumentException("数据库资源绑定版本不合法");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
    }
}
