package com.llf.ai.domain.db.adapter.repository;

import com.llf.ai.domain.db.model.entity.DbConnectionEntity;

import java.util.List;

public interface IDbConnectionRepository {
    void save(DbConnectionEntity entity);

    boolean update(String ownerId, DbConnectionEntity entity, long expectedConfigVersion);

    boolean delete(String ownerId, String connectionId);

    DbConnectionEntity find(String ownerId, String connectionId);

    List<DbConnectionEntity> findAll(String ownerId);
}
