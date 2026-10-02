USE bi_db;

-- Idempotent: every deploy re-runs all migrations, so the index is created only if it is missing.
-- The upload cleanup task maps staging directories back to sessions with storageKey IN (...).

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_session'
                 AND INDEX_NAME = 'idx_upload_session_storage_key') = 0,
              'ALTER TABLE upload_session ADD INDEX idx_upload_session_storage_key (storageKey)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
