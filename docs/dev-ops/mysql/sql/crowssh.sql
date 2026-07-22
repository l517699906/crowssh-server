CREATE TABLE `ssh_connection` (
                                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                  `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接唯一标识(UUID)',
                                  `connection_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '连接名称',
                                  `host` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '主机地址',
                                  `port` int NOT NULL DEFAULT '22' COMMENT '端口号',
                                  `username` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名',
                                  `auth_type` tinyint NOT NULL DEFAULT '1' COMMENT '认证类型:1-密码,2-私钥',
                                  `password` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '密码(加密存储)',
                                  `private_key` longtext COLLATE utf8mb4_unicode_ci COMMENT '私钥内容(加密存储)',
                                  `encrypted` tinyint NOT NULL DEFAULT '1' COMMENT '是否加密:0-否,1-是',
                                  `status` tinyint NOT NULL DEFAULT '0' COMMENT '连接状态:0-未连接,1-已连接,2-连接中,3-连接失败',
                                  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT '用户ID',
                                  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除:0-未删除,1-已删除',
                                  PRIMARY KEY (`id`),
                                  UNIQUE KEY `uk_connection_id` (`connection_id`),
                                  KEY `idx_user_id` (`user_id`),
                                  KEY `idx_status` (`status`),
                                  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接配置表';


CREATE TABLE `ssh_connection_config` (
                                         `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                         `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '关联的连接ID',
                                         `connect_timeout` int NOT NULL DEFAULT '10' COMMENT '连接超时时间(秒)',
                                         `keepalive_interval` int NOT NULL DEFAULT '60' COMMENT '保活间隔(秒)',
                                         `startup_command` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '连接后执行的启动命令',
                                         `compression` tinyint NOT NULL DEFAULT '0' COMMENT '是否压缩:0-否,1-是',
                                         `strict_host_key_check` tinyint NOT NULL DEFAULT '1' COMMENT '严格主机密钥检查:0-否,1-是',
                                         `known_hosts` longtext COLLATE utf8mb4_unicode_ci COMMENT '已知主机密钥列表',
                                         `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                                         PRIMARY KEY (`id`),
                                         UNIQUE KEY `uk_connection_id` (`connection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH连接高级配置表';
