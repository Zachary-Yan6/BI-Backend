package com.zachary.BI.model.vo;

import lombok.Data;

@Data
public class UploadChunkResponse {

    private String uploadId;
    private Integer chunkIndex;

    /**
     * True when this exact chunk was already stored by an earlier request.
     */
    private boolean alreadyUploaded;

    private Integer uploadedChunks;
    private Integer totalChunks;

    /**
     * True only when every chunk has been accepted.
     */
    private boolean readyToComplete;
}