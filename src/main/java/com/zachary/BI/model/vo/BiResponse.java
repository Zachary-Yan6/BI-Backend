package com.zachary.BI.model.vo;

import lombok.Data;

@Data
public class BiResponse {

    private String genChart;

    private String genResult;

    private Long chartId;

    private Long jobId;

    private boolean reused;
}
