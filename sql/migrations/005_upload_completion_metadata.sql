USE bi_db;

-- Idempotent: every deploy re-runs all migrations. Each column is added only if it is missing.
-- See 003_chart_field_addition.sql for why this uses information_schema + a prepared statement.

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_session' AND COLUMN_NAME = 'completedStorageKey') = 0,
              'ALTER TABLE upload_session ADD COLUMN completedStorageKey VARCHAR(256) NULL COMMENT ''Private completed-file path relative to the staging root''',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_session' AND COLUMN_NAME = 'wholeFileSha256') = 0,
              'ALTER TABLE upload_session ADD COLUMN wholeFileSha256 CHAR(64) NULL COMMENT ''SHA-256 of the merged upload''',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_session' AND COLUMN_NAME = 'completionClaimId') = 0,
              'ALTER TABLE upload_session ADD COLUMN completionClaimId VARCHAR(64) NULL COMMENT ''Identifier for the process currently completing this upload''',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_session' AND COLUMN_NAME = 'completionStartedAt') = 0,
              'ALTER TABLE upload_session ADD COLUMN completionStartedAt DATETIME NULL COMMENT ''When the current completion attempt began''',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
