USE bi_db;

-- Idempotent: every deploy re-runs all migrations, so the index is created only if it is missing.
-- The recovery task scans analysis_job by status every minute; without this index that is a full table scan
-- on a table that only ever grows.

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_job'
                 AND INDEX_NAME = 'idx_analysis_job_status_update') = 0,
              'ALTER TABLE analysis_job ADD INDEX idx_analysis_job_status_update (status, updateTime)',
              'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
