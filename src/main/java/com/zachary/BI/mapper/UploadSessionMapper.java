package com.zachary.BI.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachary.BI.model.entity.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

public interface UploadSessionMapper extends BaseMapper<UploadSession> {
    @Update("""
    UPDATE upload_session
    SET status = #{completingStatus},
        completionClaimId = #{claimId},
        completionStartedAt = NOW(),
        updateTime = NOW()
    WHERE id = #{id}
      AND userId = #{userId}
      AND expiresAt > NOW()
      AND status IN (#{createdStatus}, #{uploadingStatus})
    """)
    int claimCompletion(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("claimId") String claimId,
            @Param("completingStatus") String completingStatus,
            @Param("createdStatus") String createdStatus,
            @Param("uploadingStatus") String uploadingStatus
    );

    @Update("""
    UPDATE upload_session
    SET status = #{completedStatus},
        completedStorageKey = #{completedStorageKey},
        wholeFileSha256 = #{wholeFileSha256},
        completedAt = NOW(),
        completionClaimId = NULL,
        completionStartedAt = NULL,
        updateTime = NOW()
    WHERE id = #{id}
      AND userId = #{userId}
      AND status = #{completingStatus}
      AND completionClaimId = #{claimId}
    """)
    int markCompleted(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("claimId") String claimId,
            @Param("completedStatus") String completedStatus,
            @Param("completingStatus") String completingStatus,
            @Param("completedStorageKey") String completedStorageKey,
            @Param("wholeFileSha256") String wholeFileSha256
    );

    @Update("""
    UPDATE upload_session
    SET status = #{uploadingStatus},
        completionClaimId = NULL,
        completionStartedAt = NULL,
        updateTime = NOW()
    WHERE id = #{id}
      AND userId = #{userId}
      AND status = #{completingStatus}
      AND completionClaimId = #{claimId}
    """)
    int releaseCompletion(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("claimId") String claimId,
            @Param("uploadingStatus") String uploadingStatus,
            @Param("completingStatus") String completingStatus
    );

    /**
     * Releases completion claims whose process died mid-merge. Chunks are kept, so the user can complete again.
     * completionStartedAt is written with NOW(), so the lease is measured with the database clock.
     */
    @Update("""
    UPDATE upload_session
    SET status = 'uploading',
        completionClaimId = NULL,
        completionStartedAt = NULL,
        updateTime = NOW()
    WHERE status = 'completing'
      AND completionStartedAt < NOW() - INTERVAL #{leaseSeconds} SECOND
    """)
    int releaseStaleCompletions(@Param("leaseSeconds") long leaseSeconds);

    /**
     * Expires unfinished sessions. expiresAt is written by the JVM, so the cutoff comes from the JVM as well.
     */
    @Update("""
    UPDATE upload_session
    SET status = 'expired',
        updateTime = NOW()
    WHERE status IN ('created', 'uploading')
      AND expiresAt < #{now}
    """)
    int expireAbandonedSessions(@Param("now") Date now);

    /**
     * Ends the retention period of completed uploads. completedAt is written with NOW().
     */
    @Update("""
    UPDATE upload_session
    SET status = 'expired',
        updateTime = NOW()
    WHERE status = 'completed'
      AND completedAt < NOW() - INTERVAL #{retentionSeconds} SECOND
    """)
    int expireCompletedUploads(@Param("retentionSeconds") long retentionSeconds);
}