package com.zachary.BI.model.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Returned after the backend creates a resumable upload session.
 */
@Data
public class CreateUploadSessionResponse {

    /**
     * Public UUID used in chunk-upload URLs.
     */
    private String uploadId;

    /**
     * Backend-defined size for every non-final chunk.
     */
    private Integer chunkSize;

    /**
     * Number of chunks required to finish the upload.
     */
    private Integer totalChunks;

    /**
     * Allows the frontend to resume after a page refresh or network failure.
     * A new session returns an empty list.
     */
    private List<Integer> uploadedChunkIndexes;

    /**
     * The frontend must complete upload before this deadline.
     */
    private Date expiresAt;
}