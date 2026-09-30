package com.zachary.BI.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bi.upload")
@Data
public class UploadProperties {

    /**
     * Private directory for incomplete uploads.
     * Never serve this directory directly through HTTP.
     */
    private String stagingDirectory;

    /**
     * Maximum total size of one resumable upload.
     */
    private long maxFileSizeBytes;

    /**
     * Backend-defined chunk size.
     */
    private int chunkSizeBytes;

    /**
     * Lifetime of incomplete upload sessions.
     */
    private int sessionTtlHours;
}