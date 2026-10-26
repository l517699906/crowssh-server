-- CrowSSH 数据库 Agent 1a 增量迁移（MySQL 8.0.32+）。
--
-- 发布前必须：
-- 1. 备份当前业务库，并确认备份可恢复；
-- 2. 在目标库执行本文件开头的前置检查，表/列存在但结构不匹配时立即停止；
-- 3. 在维护窗口执行一次；DDL 不依赖应用事务回滚；
-- 4. 执行文件末尾核验，并把脚本版本、SHA-256、开始/结束时间和结果写入发布记录；
-- 5. 核验通过后再发布应用。新环境直接使用 sql/crowssh.sql，不重复执行本文件。
--
-- 本文件没有 ADD COLUMN IF NOT EXISTS：MySQL 8.0 不支持该语法。

SET @crowssh_db_name := DATABASE();
SELECT IF(@crowssh_db_name IS NULL OR @crowssh_db_name = '',
          'STOP: 请先 USE 目标应用数据库',
          'OK: 已选择目标应用数据库') AS preflight_database;

SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @crowssh_db_name AND TABLE_NAME = 'ssh_connection'
), 'OK: ssh_connection 存在', 'STOP: 缺少 ssh_connection，请先应用基础基线') AS preflight_ssh;

SELECT IF(EXISTS(
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @crowssh_db_name AND TABLE_NAME = 'chat_session'
), 'OK: chat_session 存在', 'STOP: 缺少 chat_session，请先应用聊天历史基线') AS preflight_chat;

DELIMITER $$

CREATE PROCEDURE crowssh_migrate_db_connection()
main: BEGIN
    DECLARE table_exists INT DEFAULT 0;
    DECLARE column_exists INT DEFAULT 0;
    DECLARE sql_text TEXT;
    DECLARE created_here INT DEFAULT 0;
    -- 绑定列添加失败时撤销本次刚创建的空表，使库回到未迁移状态，可在排障后直接重跑。
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF created_here = 1 THEN
            DROP TABLE IF EXISTS `db_connection`;
        END IF;
        RESIGNAL;
    END;

    IF DATABASE() IS NULL OR DATABASE() = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: 请先 USE 目标应用数据库';
    END IF;

    SELECT COUNT(*) INTO table_exists FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ssh_connection';
    IF table_exists = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: ssh_connection 不存在';
    END IF;

    SELECT COUNT(*) INTO table_exists FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session';
    IF table_exists = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: chat_session 不存在';
    END IF;

    SELECT COUNT(*) INTO table_exists FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_connection';
    IF table_exists <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: db_connection 已存在，本迁移仅允许完整未应用基线';
    END IF;
    SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session'
        AND COLUMN_NAME IN ('db_connection_id', 'db_session_id');
    IF column_exists <> 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: 已存在 DB 绑定列，部分或重复迁移须人工核对';
    END IF;
    SELECT COUNT(*) INTO column_exists FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session' AND COLUMN_NAME = 'terminal_session_id';
    IF column_exists <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'STOP: 缺少 terminal_session_id 基线列';
    END IF;
    -- 前置检查已保证 db_connection 与两个绑定列均不存在，以下步骤只走创建路径。
    SET sql_text = 'CREATE TABLE `db_connection` (\n'
        ' `id` bigint NOT NULL AUTO_INCREMENT,\n'
        ' `connection_id` varchar(64) NOT NULL,\n'
        ' `connection_name` varchar(128) NOT NULL,\n'
        ' `db_type` varchar(20) NOT NULL DEFAULT ''MYSQL'',\n'
        ' `host` varchar(255) NOT NULL,\n'
        ' `port` int NOT NULL DEFAULT 3306,\n'
        ' `username` varchar(128) NOT NULL,\n'
        ' `password` text,\n'
        ' `default_database` varchar(64) DEFAULT NULL,\n'
        ' `ssl_mode` varchar(20) NOT NULL DEFAULT ''VERIFY_IDENTITY'',\n'
        ' `tls_server_name` varchar(255) DEFAULT NULL,\n'
        ' `ca_certificate_pem` text DEFAULT NULL,\n'
        ' `tunnel_ssh_connection_id` varchar(64) DEFAULT NULL,\n'
        ' `connect_timeout` int NOT NULL DEFAULT 10,\n'
        ' `query_timeout` int NOT NULL DEFAULT 60,\n'
        ' `max_rows` int NOT NULL DEFAULT 1000,\n'
        ' `config_version` bigint NOT NULL DEFAULT 1,\n'
        ' `ai_data_mode` varchar(20) NOT NULL DEFAULT ''METADATA_ONLY'',\n'
        ' `ai_allowed_columns` json DEFAULT NULL,\n'
        ' `status` tinyint NOT NULL DEFAULT 0,\n'
        ' `user_id` varchar(64) NOT NULL,\n'
        ' `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,\n'
        ' `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,\n'
        ' `deleted` tinyint NOT NULL DEFAULT 0,\n'
        ' PRIMARY KEY (`id`), UNIQUE KEY `uk_connection_id` (`connection_id`),\n'
        ' KEY `idx_user_deleted` (`user_id`,`deleted`),\n'
        ' KEY `idx_tunnel_connection` (`tunnel_ssh_connection_id`)\n'
        ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci';
    SET @sql_text = sql_text;
    PREPARE crowssh_stmt FROM @sql_text;
    EXECUTE crowssh_stmt;
    DEALLOCATE PREPARE crowssh_stmt;
    SET created_here = 1;

    -- 两个绑定列在同一条 ALTER 中添加，不会出现只加了一列的中间状态。
    SET @sql_text = 'ALTER TABLE `chat_session`'
        ' ADD COLUMN `db_connection_id` varchar(64) DEFAULT NULL COMMENT ''绑定的数据库连接 ID'' AFTER `terminal_session_id`,'
        ' ADD COLUMN `db_session_id` varchar(64) DEFAULT NULL COMMENT ''绑定的数据库工作台会话 ID'' AFTER `db_connection_id`';
    PREPARE crowssh_stmt FROM @sql_text; EXECUTE crowssh_stmt; DEALLOCATE PREPARE crowssh_stmt;
END$$

CALL crowssh_migrate_db_connection()$$
DROP PROCEDURE crowssh_migrate_db_connection$$

DELIMITER ;

-- 应用后核验：必须看到 db_connection、两个 chat_session 列和目标索引。
SELECT TABLE_NAME, TABLE_TYPE
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_connection';

SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_session'
   AND COLUMN_NAME IN ('db_connection_id', 'db_session_id')
 ORDER BY ORDINAL_POSITION;

SELECT INDEX_NAME, COLUMN_NAME
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'db_connection'
   AND INDEX_NAME IN ('uk_connection_id', 'idx_user_deleted', 'idx_tunnel_connection')
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 若执行中断：过程内失败会撤销本次新建的 db_connection；客户端断开等外部中断仍须先按上述查询确认实际状态。
