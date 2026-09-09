package com.llf.ai.domain.db.adapter.port;

import com.llf.ai.domain.db.model.entity.DbQueryResultEntity;
import com.llf.ai.domain.db.model.valobj.DbInspectionRequest;

public interface IDbMetadataPort {
    DbQueryResultEntity inspect(String handleId, String executionId, DbInspectionRequest request,
                                int maxRows, int timeoutSeconds);
}
