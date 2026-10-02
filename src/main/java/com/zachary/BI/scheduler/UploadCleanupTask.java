package com.zachary.BI.scheduler;

import com.zachary.BI.service.UploadCleanupService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * Periodic housekeeping for resumable uploads: releases abandoned completion claims, expires unfinished and
 * retention-expired uploads, then deletes the staging directories nothing needs any more.
 */
@Component
@ConditionalOnProperty(prefix = "bi.upload.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class UploadCleanupTask {

    @Resource
    private UploadCleanupService uploadCleanupService;

    @Scheduled(initialDelayString = "${bi.upload.cleanup.interval:PT15M}",
            fixedDelayString = "${bi.upload.cleanup.interval:PT15M}")
    public void cleanUp() {
        // Status changes come first so the storage sweep below already sees this run's expirations.
        run("Released stale upload completions", uploadCleanupService::releaseStaleCompletions);
        run("Expired abandoned upload sessions", uploadCleanupService::expireAbandonedSessions);
        run("Expired completed uploads past retention", uploadCleanupService::expireCompletedUploads);
        run("Deleted unused upload directories", uploadCleanupService::deleteUnusedStorage);
    }

    private void run(String description, IntSupplier step) {
        try {
            int count = step.getAsInt();
            if (count > 0) {
                log.info("{}: {}", description, count);
            }
        } catch (RuntimeException exception) {
            // A failing step must not prevent the others from running.
            log.error("Upload cleanup step failed: {}", description, exception);
        }
    }
}
