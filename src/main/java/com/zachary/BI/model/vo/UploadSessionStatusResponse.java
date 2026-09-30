package com.zachary.BI.model.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
public class UploadSessionStatusResponse {

    /**
     * Public upload UUID received by the frontend.
     */
    private String uploadId;

    /**
     * created / uploading / completed / expired / aborted
     */
    private String status;

    private Integer chunkSize;
    private Integer totalChunks;

    /**
     * Used by the frontend to skip chunks that were already received.
     */
    private List<Integer> uploadedChunkIndexes;

    private Date expiresAt;

    /**
     * True only when every required chunk has reached the server.
     */
    private boolean readyToComplete;
}