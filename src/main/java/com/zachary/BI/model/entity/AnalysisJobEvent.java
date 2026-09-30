package com.zachary.BI.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@Data
@TableName("analysis_job_event")
public class AnalysisJobEvent {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long jobId;
    private String status;
    private String message;
    private Date createTime;
}
