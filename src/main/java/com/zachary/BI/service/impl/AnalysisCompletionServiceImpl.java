package com.zachary.BI.service.impl;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.ThrowUtils;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.enums.AnalysisJobStatusEnum;
import com.zachary.BI.service.AnalysisCompletionService;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Date;

@Service
public class AnalysisCompletionServiceImpl implements AnalysisCompletionService {

    @Resource
    private ChartService chartService;

    @Resource
    private AnalysisJobService analysisJobService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistSuccess(long jobId, long chartId, String genChart, String genResult) {
        Chart completedChart = new Chart();
        completedChart.setId(chartId);
        completedChart.setStatus(AnalysisJobStatusEnum.SUCCEEDED.getValue());
        completedChart.setGenChart(genChart);
        completedChart.setGenResult(genResult);

        // This is the real AI completion time, used by the generated-time filter.
        completedChart.setGeneratedAt(new Date());

        boolean chartUpdated = chartService.updateById(completedChart);

        // Throwing here makes Spring roll back this chart update.
        ThrowUtils.throwIf(!chartUpdated, ErrorCode.OPERATION_ERROR,
                "Failed to persist the completed chart.");

        // This joins the same transaction by default.
        // If it fails, the chart update above is rolled back too.
        analysisJobService.succeed(jobId);
    }
}