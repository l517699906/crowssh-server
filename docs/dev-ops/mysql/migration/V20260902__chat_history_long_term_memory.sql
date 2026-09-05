-- CrowSSH 2-8: chat history and long-term memory tables.
-- Run this script after selecting the application database (normally `crowssh`).
-- The statements are idempotent for installations that already applied the full SQL dump.

CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` varchar(64) NOT NULL COMMENT '会话ID',
    `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
    `user_id` varchar(64) NOT NULL COMMENT '设备身份ID',
    `connection_id` varchar(64) DEFAULT NULL COMMENT '绑定的 SSH 连接 ID',
    `terminal_session_id` varchar(64) DEFAULT NULL COMMENT '绑定的 SSH 终端会话 ID',
    `title` varchar(200) DEFAULT NULL COMMENT '会话标题',
    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `message_count` int DEFAULT '0' COMMENT '消息数量',
    PRIMARY KEY (`id`),
    KEY `idx_user_agent` (`user_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话会话';

-- Older 2-8 installations may already have chat_session without SSH binding columns.
ALTER TABLE `chat_session`
    ADD COLUMN IF NOT EXISTS `connection_id` varchar(64) DEFAULT NULL COMMENT '绑定的 SSH 连接 ID' AFTER `user_id`,
    ADD COLUMN IF NOT EXISTS `terminal_session_id` varchar(64) DEFAULT NULL COMMENT '绑定的 SSH 终端会话 ID' AFTER `connection_id`;

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `session_id` varchar(64) NOT NULL COMMENT '会话ID',
    `role` varchar(20) NOT NULL COMMENT '角色: user/assistant/tool/system',
    `content` text COMMENT '消息内容',
    `tool_name` varchar(100) DEFAULT NULL COMMENT '工具名称',
    `tool_call_id` varchar(100) DEFAULT NULL COMMENT '工具调用ID',
    `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT '优先级: CRITICAL/HIGH/MEDIUM/LOW',
    `token_count` int DEFAULT '0' COMMENT '预估 token 数',
    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话消息';

CREATE TABLE IF NOT EXISTS `chat_milestone` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `session_id` varchar(64) NOT NULL COMMENT '会话ID',
    `type` varchar(30) NOT NULL COMMENT '类型: TASK_CHANGE/ERROR/DECISION/...',
    `content` text COMMENT '内容摘要',
    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='对话里程碑';

CREATE TABLE IF NOT EXISTS `long_term_memory` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `user_id` varchar(64) NOT NULL COMMENT '设备身份ID',
    `session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
    `memory_type` varchar(50) NOT NULL COMMENT '记忆类型',
    `memory_key` varchar(128) NOT NULL COMMENT '记忆去重键',
    `content` text NOT NULL COMMENT '记忆内容',
    `keywords` varchar(512) DEFAULT NULL COMMENT '关键词集合',
    `source_role` varchar(20) DEFAULT NULL COMMENT '来源角色',
    `confidence` decimal(5,2) DEFAULT '0.50' COMMENT '置信度',
    `hit_count` int DEFAULT '1' COMMENT '命中/更新次数',
    `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_type_key` (`user_id`, `memory_type`, `memory_key`),
    KEY `idx_user_time` (`user_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆';
