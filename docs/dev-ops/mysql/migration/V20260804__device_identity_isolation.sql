CREATE TABLE IF NOT EXISTS `device_principal` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `principal_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '设备身份ID',
    `token_hash` char(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '访问令牌SHA-256哈希',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态:0-禁用,1-有效',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `revoked_at` datetime DEFAULT NULL COMMENT '撤销时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_principal_id` (`principal_id`),
    UNIQUE KEY `uk_token_hash` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户端设备身份表';

ALTER TABLE `ssh_connection`
    MODIFY COLUMN `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '设备身份ID';

-- 历史 user_id='default' 记录不会自动分配给新设备。
-- 首次注册后，请由管理员将需要保留的记录显式迁移到注册响应中的 principalId。
