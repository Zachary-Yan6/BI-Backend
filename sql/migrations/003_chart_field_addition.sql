use bi_db;

-- Idempotent: every deploy re-runs all migrations, and fresh databases already get these
-- columns from create_table.sql. Each change is applied only if it is missing.
-- MySQL has no "ADD COLUMN IF NOT EXISTS", so the check uses information_schema + a prepared statement.

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND COLUMN_NAME = 'sourceFileName') = 0,
              'ALTER TABLE chart ADD COLUMN sourceFileName VARCHAR(512) NULL COMMENT ''Original source filename'' AFTER chartData',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND COLUMN_NAME = 'sourceFileType') = 0,
              'ALTER TABLE chart ADD COLUMN sourceFileType VARCHAR(16) NULL COMMENT ''Original source type: csv/xlsx'' AFTER sourceFileName',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND COLUMN_NAME = 'sourceFileSize') = 0,
              'ALTER TABLE chart ADD COLUMN sourceFileSize BIGINT NULL COMMENT ''Original source size in bytes'' AFTER sourceFileType',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND COLUMN_NAME = 'generatedAt') = 0,
              'ALTER TABLE chart ADD COLUMN generatedAt DATETIME NULL COMMENT ''Successful AI generation time'' AFTER execMessage',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND INDEX_NAME = 'idx_chart_user_status') = 0,
              'ALTER TABLE chart ADD INDEX idx_chart_user_status (userId, status)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND INDEX_NAME = 'idx_chart_user_type') = 0,
              'ALTER TABLE chart ADD INDEX idx_chart_user_type (userId, chartType)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND INDEX_NAME = 'idx_chart_user_generated_at') = 0,
              'ALTER TABLE chart ADD INDEX idx_chart_user_generated_at (userId, generatedAt)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chart' AND INDEX_NAME = 'idx_chart_user_source_size') = 0,
              'ALTER TABLE chart ADD INDEX idx_chart_user_source_size (userId, sourceFileSize)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
