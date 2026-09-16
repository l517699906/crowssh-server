package com.llf.ai.domain.db.service;

import com.llf.ai.domain.db.model.entity.DbSchemaEntity;

import java.util.List;

public interface IDbMetadataService {
    java.util.Map<String, com.llf.ai.domain.db.model.valobj.DbCapabilityState> refreshCapabilities(String ownerId, String dbSessionId);

    List<String> listDatabases(String ownerId, String dbSessionId);

    List<DbSchemaEntity> listTables(String ownerId, String dbSessionId, String database);

    DbSchemaEntity describeTable(String ownerId, String dbSessionId, String database, String table);
}
