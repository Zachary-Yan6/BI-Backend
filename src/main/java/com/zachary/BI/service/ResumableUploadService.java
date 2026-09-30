package com.zachary.BI.service;

import com.zachary.BI.model.dto.upload.CompletedUploadFile;
import com.zachary.BI.model.dto.upload.CreateUploadSessionRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.CreateUploadSessionResponse;
import com.zachary.BI.model.vo.UploadChunkResponse;
import com.zachary.BI.model.vo.UploadCompletionResponse;
import com.zachary.BI.model.vo.UploadSessionStatusResponse;

import java.io.IOException;
import java.io.InputStream;

public interface ResumableUploadService {

    /**
     * Validates metadata and creates an authenticated resumable-upload session.
     */
    CreateUploadSessionResponse createSession(
            CreateUploadSessionRequest request,
            User user
    );

    UploadSessionStatusResponse getSessionStatus(String uploadId, User user);

    /**
     * Validates and stores one raw binary chunk.
     */
    UploadChunkResponse uploadChunk(
            String uploadId,
            int chunkIndex,
            String contentRange,
            String chunkSha256,
            long contentLength,
            InputStream inputStream,
            User user
    ) throws IOException;

    UploadCompletionResponse completeUpload(String uploadId, User loginUser) throws IOException;

    CompletedUploadFile resolveCompletedUpload(
            String fileToken,
            User user
    ) throws IOException;
}