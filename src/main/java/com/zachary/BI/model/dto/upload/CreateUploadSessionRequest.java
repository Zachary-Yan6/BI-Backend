package com.zachary.BI.model.dto.upload;

import lombok.Data;

import java.io.Serializable;

/**
 * Metadata submitted before any binary file chunks are sent.
 * The server—not the client—selects the actual chunk size.
 */
@Data
public class CreateUploadSessionRequest implements Serializable {

    /**
     * Original filename for display and type validation.
     * It must never be used as a server filesystem path.
     */
    private String fileName;

    /**
     * Expected total file size in bytes.
     * The server rejects files above its configured upload limit.
     */
    private Long totalSize;

    /**
     * Browser-reported MIME type. It is informational only;
     * the backend performs its own extension and content validation later.
     */
    private String contentType;

    private static final long serialVersionUID = 1L;
}