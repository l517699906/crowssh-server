package com.llf.ai.infrastructure.adapter.repository;

import com.llf.ai.domain.agent.adapter.repository.ILongTermMemoryRepository;
import com.llf.ai.domain.agent.model.entity.LongTermMemoryEntity;
import com.llf.ai.infrastructure.dao.ILongTermMemoryDao;
import com.llf.ai.infrastructure.dao.po.LongTermMemoryPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 长期记忆仓储实现（基础设施层）
 * <p>
 * 实现 {@link ILongTermMemoryRepository} 接口，通过 MyBatis DAO 操作数据库。
 * <p>
 * 核心逻辑：
 * <ul>
 *   <li>{@link #saveOrUpdate} - 使用数据库唯一键执行原子 Upsert；不存在则 INSERT，
 *       已存在则更新内容并递增 hit_count</li>
 *   <li>{@link #queryRecentByUserId} - 按 updated_at DESC 排序取最近 N 条（粗筛阶段）</li>
 * </ul>
 * <p>
 * 使用 INSERT ... ON DUPLICATE KEY UPDATE 保证并发写入幂等，
 * 避免两个请求同时读取不到记录后都执行 INSERT。
 *
 * @see ILongTermMemoryRepository
 */
@Repository
public class LongTermMemoryRepository implements ILongTermMemoryRepository {

    @Resource
    private ILongTermMemoryDao longTermMemoryDao;

    /**
     * {@inheritDoc}
     * <p>
     * Upsert 由 MySQL 唯一键在单条语句内完成，已存在记录会原子递增 hit_count。
     */
    @Override
    public void saveOrUpdate(LongTermMemoryEntity memoryEntity) {
        if (memoryEntity == null) {
            return;
        }
        // 由数据库唯一键保证并发幂等，避免“先查后插”在两个请求之间发生竞态。
        longTermMemoryDao.upsert(toPO(memoryEntity));
    }

    /**
     * {@inheritDoc}
     * <p>
     * SQL: ORDER BY updated_at DESC, id DESC LIMIT N，取最近 N 条记忆供精排使用。
     */
    @Override
    public List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit) {
        List<LongTermMemoryPO> list = longTermMemoryDao.queryRecentByUserId(userId, limit);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(this::toEntity).collect(Collectors.toList());
    }

    // ==================== PO ↔ Entity 转换 ====================

    private LongTermMemoryPO toPO(LongTermMemoryEntity entity) {
        return LongTermMemoryPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .sessionId(entity.getSessionId())
                .memoryType(entity.getMemoryType())
                .memoryKey(entity.getMemoryKey())
                .content(entity.getContent())
                .keywords(entity.getKeywords())
                .sourceRole(entity.getSourceRole())
                .confidence(entity.getConfidence())
                .hitCount(entity.getHitCount())
                .build();
    }

    private LongTermMemoryEntity toEntity(LongTermMemoryPO po) {
        return LongTermMemoryEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .memoryType(po.getMemoryType())
                .memoryKey(po.getMemoryKey())
                .content(po.getContent())
                .keywords(po.getKeywords())
                .sourceRole(po.getSourceRole())
                .confidence(po.getConfidence())
                .hitCount(po.getHitCount())
                .createdAt(po.getCreatedAt() != null ? new Date(Timestamp.valueOf(po.getCreatedAt()).getTime()) : null)
                .updatedAt(po.getUpdatedAt() != null ? new Date(Timestamp.valueOf(po.getUpdatedAt()).getTime()) : null)
                .build();
    }
}
