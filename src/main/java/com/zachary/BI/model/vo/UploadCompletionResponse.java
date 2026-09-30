package com.zachary.BI.model.vo;

import lombok.Data;

import java.util.Date;

@Data
public class UploadCompletionResponse {

    /**
     * Opaque identifier used by later quality-check and generation APIs.
     * It must always be resolved together with the authenticated user ID.
     */
    private String fileToken;

    private String originalFileName;

    private String sourceFileType;

    private Long totalSize;

    private String wholeFileSha256;

    private Date completedAt;
}