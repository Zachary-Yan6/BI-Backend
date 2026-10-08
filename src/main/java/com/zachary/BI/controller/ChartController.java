package com.zachary.BI.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.annotation.AuthCheck;
import com.zachary.BI.common.BaseResponse;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.common.ResultUtils;
import com.zachary.BI.constant.UserConstant;
import com.zachary.BI.model.dto.chart.*;
import com.zachary.BI.model.dto.upload.FileTokenRequest;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.BiResponse;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.service.ChartApplicationService;
import com.zachary.BI.service.UserService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * HTTP adapter for chart endpoints. All chart business rules live in ChartApplicationService.
 */
@RestController
@RequestMapping("/chart")
public class ChartController {
    @Resource
    private ChartApplicationService chartApplicationService;
    @Resource
    private UserService userService;

    @PostMapping("/add")
    public BaseResponse<Long> addChart(@RequestBody ChartAddRequest request, HttpServletRequest httpRequest) {
        return ResultUtils.success(chartApplicationService.addChart(request, userService.getLoginUser(httpRequest)));
    }

    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteChart(@RequestBody DeleteRequest request, HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(chartApplicationService.deleteChart(request, user, userService.isAdmin(httpRequest)));
    }

    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateChart(@RequestBody ChartUpdateRequest request) {
        return ResultUtils.success(chartApplicationService.updateChartAsAdmin(request));
    }

    @GetMapping("/get")
    public BaseResponse<Chart> getChartVOById(long id, HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(chartApplicationService.getChart(id, user, userService.isAdmin(user)));
    }

    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Chart>> listChartByPage(@RequestBody ChartQueryRequest request) {
        return ResultUtils.success(chartApplicationService.listCharts(request));
    }

    /**
     * Charts are private, so this returns only the caller's charts, exactly like /my/list/page/vo.
     *
     * @deprecated kept so existing clients keep working; use /my/list/page/vo.
     */
    @Deprecated
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<Chart>> listChartVOByPage(@RequestBody ChartQueryRequest request,
                                                       HttpServletRequest httpRequest) {
        return listMyChartVOByPage(request, httpRequest);
    }

    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<Chart>> listMyChartVOByPage(@RequestBody ChartQueryRequest request,
                                                         HttpServletRequest httpRequest) {
        return ResultUtils.success(chartApplicationService.listMyCharts(request, userService.getLoginUser(httpRequest)));
    }

    @PostMapping("/edit")
    public BaseResponse<Boolean> editChart(@RequestBody ChartEditRequest request, HttpServletRequest httpRequest) {
        User user = userService.getLoginUser(httpRequest);
        return ResultUtils.success(chartApplicationService.editChart(request, user, userService.isAdmin(user)));
    }

    /**
     * Legacy multipart endpoint retained for small existing clients.
     */
    @PostMapping(value = "/gen", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<BiResponse> genChartByAI(@RequestPart("file") MultipartFile file,
                                                 GenChartByAIRequest request,
                                                 HttpServletRequest httpRequest) throws Exception {
        return ResultUtils.success(chartApplicationService.generateChart(file, request, userService.getLoginUser(httpRequest)));
    }

    /**
     * Uses a completed resumable upload, so the data file is not sent to the server again.
     */
    @PostMapping(value = "/gen", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaseResponse<BiResponse> genChartByUploadedFile(@RequestBody GenChartByAIRequest request,
                                                            HttpServletRequest httpRequest) throws Exception {
        return ResultUtils.success(chartApplicationService.generateChart(
                request == null ? null : request.getFileToken(),
                request,
                userService.getLoginUser(httpRequest)
        ));
    }

    /**
     * Legacy multipart endpoint retained for small existing clients.
     */
    @PostMapping(value = "/quality-check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<DataQualityReport> checkDataQuality(@RequestPart("file") MultipartFile file,
                                                             HttpServletRequest httpRequest) throws Exception {
        return ResultUtils.success(chartApplicationService.inspectData(file, userService.getLoginUser(httpRequest)));
    }

    /**
     * Performs a quality check against a private completed upload.
     */
    @PostMapping(value = "/quality-check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BaseResponse<DataQualityReport> checkUploadedDataQuality(@RequestBody FileTokenRequest request,
                                                                     HttpServletRequest httpRequest) throws Exception {
        return ResultUtils.success(chartApplicationService.inspectData(
                request == null ? null : request.getFileToken(),
                userService.getLoginUser(httpRequest)
        ));
    }

    @PostMapping("/job/list-by-chart")
    public BaseResponse<List<AnalysisJob>> listJobsByChart(@RequestBody JobIdsRequest request,
                                                            HttpServletRequest httpRequest) {
        List<Long> chartIds = request == null ? List.of() : request.getChartIds();
        return ResultUtils.success(chartApplicationService.listJobs(chartIds, userService.getLoginUser(httpRequest)));
    }

    @GetMapping("/job/{jobId}/events")
    public BaseResponse<List<AnalysisJobEvent>> listJobEvents(@PathVariable long jobId, HttpServletRequest httpRequest) {
        return ResultUtils.success(chartApplicationService.listJobEvents(jobId, userService.getLoginUser(httpRequest)));
    }

    @PostMapping("/job/{jobId}/retry")
    public BaseResponse<Boolean> retryJob(@PathVariable long jobId, HttpServletRequest httpRequest) {
        return ResultUtils.success(chartApplicationService.retryJob(jobId, userService.getLoginUser(httpRequest)));
    }

    @PostMapping("/job/{jobId}/cancel")
    public BaseResponse<Boolean> cancelJob(@PathVariable long jobId, HttpServletRequest httpRequest) {
        return ResultUtils.success(chartApplicationService.cancelJob(jobId, userService.getLoginUser(httpRequest)));
    }
}
