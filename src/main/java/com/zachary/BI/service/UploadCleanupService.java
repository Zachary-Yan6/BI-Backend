package com.zachary.BI.service;

/**
 * Lifecycle housekeeping for resumable uploads. Status changes happen in the database first;
 * {@link #deleteUnusedStorage()} then removes every directory the database no longer needs.
 */
public interface UploadCleanupService {

    /** Returns sessions stuck in completing to uploading. @return number of sessions released */
    int releaseStaleCompletions();

    /** Marks created/uploading sessions past expiresAt as expired. @return number of sessions expired */
    int expireAbandonedSessions();

    /** Marks completed uploads past their retention period as expired. @return number of uploads expired */
    int expireCompletedUploads();

    /**
     * Deletes staging directories whose session is expired, aborted, or missing entirely.
     *
     * @return number of directories deleted
     */
    int deleteUnusedStorage();
}
