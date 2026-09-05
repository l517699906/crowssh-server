package com.llf.ai.domain.agent.adapter.repository;

import com.llf.ai.domain.agent.model.entity.LongTermMemoryEntity;

import java.util.List;

/**
 * 长期记忆持久化仓储接口（领域层抽象）
 * <p>
 * 定义长期记忆的 Upsert 写入和按用户查询能力，由基础设施层
 * {@code LongTermMemoryRepository} 实现。
 * <p>
 * 长期记忆的写入采用数据库原子 Upsert：通过 (userId, memoryType, memoryKey)
 * 三元组唯一键去重，不存在则 INSERT，已存在则更新内容并递增 hit_count。
 * 这样同一偏好/事实不会重复入库，也不会在并发请求下发生先查后插竞态。
 *
 * @see com.llf.ai.infrastructure.adapter.repository.LongTermMemoryRepository
 */
public interface ILongTermMemoryRepository {

    /**
     * 保存或更新长期记忆（Upsert 模式）。
     * <p>
     * 由数据库唯一键 (userId, memoryType, memoryKey) 原子判断是否已存在：
     * <ul>
     *   <li>不存在 → INSERT 新记忆</li>
     *   <li>已存在 → UPDATE content/keywords/confidence 等字段，hit_count + 1</li>
     * </ul>
     *
     * @param memoryEntity 长期记忆实体
     */
    void saveOrUpdate(LongTermMemoryEntity memoryEntity);

    /**
     * 按用户 ID 查询最近的 N 条长期记忆（粗筛阶段）。
     * <p>
     * SQL 按 updated_at DESC, id DESC 排序取最近 N 条，
     * 真正的"搜索"在 LongTermMemoryService 中用关键词重叠打分完成（精排阶段）。
     *
     * @param userId 用户 ID
     * @param limit  最大返回条数（LongTermMemoryService 传入 120）
     * @return 长期记忆列表
     */
    List<LongTermMemoryEntity> queryRecentByUserId(String userId, int limit);
}
