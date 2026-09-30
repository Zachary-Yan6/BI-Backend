package com.zachary.BI.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("upload_chunk")
public class UploadChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Internal id of the parent upload_session record.
     */
    private Long uploadSessionId;

    /**
     * Zero-based chunk number. Example: 0, 1, 2.
     */
    private Integer chunkIndex;

    /**
     * Actual validated byte count for this chunk.
     */
    private Integer chunkSize;

    /**
     * SHA-256 checksum used to detect corrupted or conflicting retries.
     */
    private String sha256;

    private Date createTime;
}