package com.zachary.BI.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.model.dto.chart.ChartAddRequest;
import com.zachary.BI.model.dto.chart.ChartEditRequest;
import com.zachary.BI.model.dto.chart.ChartQueryRequest;
import com.zachary.BI.model.dto.chart.ChartUpdateRequest;
import com.zachary.BI.model.dto.chart.GenChartByAIRequest;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.BiResponse;
import com.zachary.BI.model.vo.DataQualityReport;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Application-level chart workflows. Controllers delegate here and remain HTTP-only adapters.
 */
public interface ChartApplicationService {
    long addChart(ChartAddRequest request, User user);
    boolean deleteChart(DeleteRequest request, User user, boolean isAdmin);
    boolean updateChartAsAdmin(ChartUpdateRequest request);
    Chart getChart(long chartId);
    Page<Chart> listCharts(ChartQueryRequest request);
    Page<Chart> listPublicCharts(ChartQueryRequest request);
    Page<Chart> listMyCharts(ChartQueryRequest request, User user);
    boolean editChart(ChartEditRequest request, User user, boolean isAdmin);
    BiResponse generateChart(MultipartFile file, GenChartByAIRequest request, User user) throws Exception;
    BiResponse generateChart(String fileToken, GenChartByAIRequest request, User user) throws Exception;
    DataQualityReport inspectData(MultipartFile file) throws Exception;
    DataQualityReport inspectData(String fileToken, User user) throws Exception;
    List<AnalysisJob> listJobs(List<Long> chartIds, User user);
    List<AnalysisJobEvent> listJobEvents(long jobId, User user);
    boolean retryJob(long jobId, User user);
    boolean cancelJob(long jobId, User user);
}
