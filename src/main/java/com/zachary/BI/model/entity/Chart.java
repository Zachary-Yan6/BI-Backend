package com.zachary.BI.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;

/**
 * Documentation.
 * @TableName chart
 */
@TableName(value ="chart")
@Data
public class Chart {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    private String goal;

    private String chartData;

    /**
     * Original filename supplied by the user, used for source-file discovery.
     */
    private String sourceFileName;

    /**
     * Original upload format, currently csv or xlsx.
     */
    private String sourceFileType;

    /**
     * Original upload size in bytes. This is not the size of the normalized CSV stored in chartData.
     */
    private Long sourceFileSize;

    private String chartType;

    private Long userId;

    private String genChart;

    private String genResult;

    private String status;

    private String execMessage;

    /**
     * The time at which AI generation successfully completed; null for unfinished or failed jobs.
     */
    private Date generatedAt;

    private Date createTime;

    private Date updateTime;

    @TableLogic
    private Integer isDelete;
}
