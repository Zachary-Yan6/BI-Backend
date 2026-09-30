package com.zachary.BI.controller;

import com.zachary.BI.common.BaseResponse;
import com.zachary.BI.common.ResultUtils;
import com.zachary.BI.model.dto.upload.CreateUploadSessionRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.CreateUploadSessionResponse;
import com.zachary.BI.model.vo.UploadChunkResponse;
import com.zachary.BI.model.vo.UploadCompletionResponse;
import com.zachary.BI.model.vo.UploadSessionStatusResponse;
import com.zachary.BI.service.ResumableUploadService;
import com.zachary.BI.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private ResumableUploadService resumableUploadService;

    @Resource
    private UserService userService;

    @PostMapping("/sessions")
    public BaseResponse<CreateUploadSessionResponse> createSession(
            @RequestBody CreateUploadSessionRequest request,
            HttpServletRequest httpRequest
    ) {
        return ResultUtils.success(
                resumableUploadService.createSession(
                        request,
                        userService.getLoginUser(httpRequest)
                )
        );
    }

    @GetMapping("/sessions/{uploadId}")
    public BaseResponse<UploadSessionStatusResponse> getSessionStatus(
            @PathVariable String uploadId,
            HttpServletRequest httpRequest
    ) {
        return ResultUtils.success(
                resumableUploadService.getSessionStatus(
                        uploadId,
                        userService.getLoginUser(httpRequest)
                )
        );
    }

    @PutMapping(
            value = "/sessions/{uploadId}/chunks/{chunkIndex}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public BaseResponse<UploadChunkResponse> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestHeader("Content-Range") String contentRange,
            @RequestHeader("X-Chunk-SHA256") String chunkSha256,
            HttpServletRequest httpRequest
    ) throws IOException {
        return ResultUtils.success(
                resumableUploadService.uploadChunk(
                        uploadId,
                        chunkIndex,
                        contentRange,
                        chunkSha256,
                        httpRequest.getContentLengthLong(),
                        httpRequest.getInputStream(),
                        userService.getLoginUser(httpRequest)
                )
        );
    }

    @PostMapping("/sessions/{uploadId}/complete")
    public BaseResponse<UploadCompletionResponse> completeUpload(
            @PathVariable String uploadId,
            HttpServletRequest request
    ) throws IOException {
        User loginUser = userService.getLoginUser(request);

        return ResultUtils.success(
                resumableUploadService.completeUpload(uploadId, loginUser)
        );
    }
}