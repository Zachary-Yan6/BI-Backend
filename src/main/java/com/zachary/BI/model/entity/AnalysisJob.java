package com.zachary.BI.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("analysis_job")
public class AnalysisJob {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long chartId;
    private Long userId;
    private String requestId;
    private String dataFingerprint;
    private String activeFingerprint;
    private String status;
    private Integer retryCount;
    private Integer maxRetries;
    private String failureReason;
    private Date startedAt;
    private Date finishedAt;
    private Date cancelledAt;
    private Date createTime;
    private Date updateTime;
}
