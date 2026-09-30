package com.zachary.BI.model.dto.upload;

import java.nio.file.Path;

/**
 * Server-only description of a verified completed upload.
 * Never return this object from a controller because it contains a disk path.
 */
public record CompletedUploadFile(
        Path path,
        String originalFileName,
        String sourceFileType,
        long totalSize,
        String wholeFileSha256
) {
}