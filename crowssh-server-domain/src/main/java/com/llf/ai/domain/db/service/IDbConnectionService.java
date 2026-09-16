package com.llf.ai.domain.db.service;

import com.llf.ai.domain.db.model.entity.DbConnectionEntity;
import com.llf.ai.domain.db.model.valobj.CaCertificateActionEnum;
import com.llf.ai.domain.db.model.valobj.PasswordActionEnum;

import java.util.List;

public interface IDbConnectionService {
    DbConnectionEntity create(String ownerId, DbConnectionEntity draft,
                              PasswordActionEnum passwordAction,
                              CaCertificateActionEnum caAction);

    DbConnectionEntity update(String ownerId, DbConnectionEntity draft,
                              long expectedConfigVersion,
                              PasswordActionEnum passwordAction,
                              CaCertificateActionEnum caAction);

    void delete(String ownerId, String connectionId);

    DbConnectionEntity get(String ownerId, String connectionId);

    List<DbConnectionEntity> list(String ownerId);

    com.llf.ai.domain.db.adapter.port.IDbSessionPort.TestConnectionResult test(
            String ownerId, DbConnectionEntity draft, String connectionId,
            PasswordActionEnum passwordAction, CaCertificateActionEnum caAction);
}
