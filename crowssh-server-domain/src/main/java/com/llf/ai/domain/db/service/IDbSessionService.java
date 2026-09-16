package com.llf.ai.domain.db.service;

import com.llf.ai.domain.db.model.entity.DbExecutionEntity;
import com.llf.ai.domain.db.model.entity.DbSessionEntity;
import com.llf.ai.domain.db.model.valobj.DbExecutionSourceEnum;

import java.util.List;

public interface IDbSessionService {
    DbSessionEntity open(String ownerId, String connectionId);

    DbExecutionEntity prepare(String ownerId, String dbSessionId, String sql,
                              long expectedTargetContextVersion, DbExecutionSourceEnum source);

    DbExecutionEntity execute(String ownerId, String dbSessionId, String executionId);

    default DbExecutionEntity executeAsync(String ownerId, String dbSessionId, String executionId) {
        throw new IllegalStateException("数据库异步执行器未配置");
    }

    DbExecutionEntity cancel(String ownerId, String dbSessionId, String executionId);

    DbExecutionEntity getExecution(String ownerId, String dbSessionId, String executionId);

    DbSessionEntity selectDatabase(String ownerId, String dbSessionId, String database,
                                   long expectedTargetContextVersion);

    void close(String ownerId, String dbSessionId);

    List<DbSessionEntity> list(String ownerId);
}
