package controllerTest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.common.BaseResponse;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.controller.ChartController;
import com.zachary.BI.model.dto.chart.ChartAddRequest;
import com.zachary.BI.model.dto.chart.ChartEditRequest;
import com.zachary.BI.model.dto.chart.ChartQueryRequest;
import com.zachary.BI.model.dto.chart.ChartUpdateRequest;
import com.zachary.BI.model.dto.chart.GenChartByAIRequest;
import com.zachary.BI.model.dto.chart.JobIdsRequest;
import com.zachary.BI.model.dto.upload.FileTokenRequest;
import com.zachary.BI.model.entity.AnalysisJob;
import com.zachary.BI.model.entity.AnalysisJobEvent;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.BiResponse;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.service.ChartApplicationService;
import com.zachary.BI.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.AssertionsKt.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ChartControllerTest {

    @Mock
    private ChartApplicationService chartApplicationService;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private ChartController chartController;

    @Test
    void addChart_shouldDelegateToApplicationServiceForLoggedInUser() {
        // Arrange: construct the authenticated user and request data.
        User loggedInUser = new User();
        loggedInUser.setId(101L);

        ChartAddRequest chartAddRequest = new ChartAddRequest();
        Long expectedChartId = 5001L;

        when(userService.getLoginUser(request)).thenReturn(loggedInUser);
        when(chartApplicationService.addChart(chartAddRequest, loggedInUser))
                .thenReturn(expectedChartId);

        // Act: call the controller directly, without starting Spring.
        BaseResponse<Long> response = chartController.addChart(chartAddRequest, request);

        // Assert: confirm both the HTTP result and service collaboration.
        assertEquals(expectedChartId, response.getData());
        verify(userService).getLoginUser(request);
        verify(chartApplicationService).addChart(chartAddRequest, loggedInUser);
    }

    @Test
    void generateChartByFileToken_shouldDelegateToApplicationService() throws Exception {
        // Arrange: the browser has uploaded the file and now sends only its file token.
        User loggedInUser = new User();
        loggedInUser.setId(101L);

        GenChartByAIRequest chartRequest = new GenChartByAIRequest();
        chartRequest.setFileToken("completed-upload-token");
        chartRequest.setName("Monthly Revenue");
        chartRequest.setGoal("Show the monthly revenue trend");
        chartRequest.setChartType("line");
        chartRequest.setQualityAcknowledged(true);

        BiResponse expectedResult = new BiResponse();
        expectedResult.setChartId(5002L);

        when(userService.getLoginUser(request)).thenReturn(loggedInUser);
        when(chartApplicationService.generateChart(
                "completed-upload-token",
                chartRequest,
                loggedInUser
        )).thenReturn(expectedResult);

        // Act: invoke the JSON controller route directly.
        BaseResponse<BiResponse> response = chartController.genChartByUploadedFile(chartRequest, request);

        // Assert: controller forwards token and metadata, but handles no file data itself.
        assertEquals(expectedResult, response.getData());
        verify(userService).getLoginUser(request);
        verify(chartApplicationService).generateChart(
                "completed-upload-token",
                chartRequest,
                loggedInUser
        );
    }

    @Test
    void checkDataQuality_whenAuthenticated_shouldInspectUploadedFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        User user = new User();
        user.setId(101L);
        when(userService.getLoginUser(request)).thenReturn(user);

        BaseResponse<?> response = chartController.checkDataQuality(file, request);

        assertNotNull(response);
        verify(userService).getLoginUser(request);
        verify(chartApplicationService).inspectData(file, user);
    }

    @Test
    void checkDataQuality_whenAuthenticationFails_shouldNotInspectFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(userService.getLoginUser(request))
                .thenThrow(new IllegalStateException("Not authenticated"));

        assertThrows(IllegalStateException.class,
                () -> chartController.checkDataQuality(file, request));

        verifyNoInteractions(chartApplicationService);
    }

    @Test
    void checkUploadedDataQuality_shouldInspectTokenForCurrentUser() throws Exception {

        FileTokenRequest tokenRequest = new FileTokenRequest();
        tokenRequest.setFileToken("upload-123");
        User user = new User();
        user.setId(101L);
        DataQualityReport report = mock(DataQualityReport.class);

        when(userService.getLoginUser(request)).thenReturn(user);
        when(chartApplicationService.inspectData("upload-123", user)).thenReturn(report);

        BaseResponse<DataQualityReport> response = chartController.checkUploadedDataQuality(tokenRequest, request);
        assertSame(report, response.getData());

        verify(userService).getLoginUser(request);
        verify(chartApplicationService).inspectData("upload-123", user);

    }

    @Test
    void checkUploadedDataQuality_whenAuthenticationFails_shouldNotInspectToken() {
        FileTokenRequest tokenRequest = new FileTokenRequest();
        tokenRequest.setFileToken("upload-123");

        when(userService.getLoginUser(request))
                .thenThrow(new IllegalStateException("Not authenticated"));

        assertThrows(IllegalStateException.class,
                () -> chartController.checkUploadedDataQuality(tokenRequest, request));

        verifyNoInteractions(chartApplicationService);
    }

    @Test
    void deleteChart_shouldPassAdminFlagFromRequest() {
        User user = loggedInUser();
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.setId(7L);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(userService.isAdmin(request)).thenReturn(true);
        when(chartApplicationService.deleteChart(deleteRequest, user, true)).thenReturn(true);

        BaseResponse<Boolean> response = chartController.deleteChart(deleteRequest, request);

        assertTrue(response.getData());
    }

    @Test
    void updateChart_shouldDelegateAdminUpdate() {
        ChartUpdateRequest updateRequest = new ChartUpdateRequest();
        when(chartApplicationService.updateChartAsAdmin(updateRequest)).thenReturn(true);

        assertTrue(chartController.updateChart(updateRequest).getData());
    }

    @Test
    void getChartVOById_shouldPassLoggedInUserAndAdminFlag() {
        Chart chart = new Chart();
        User user = loggedInUser();
        when(userService.getLoginUser(request)).thenReturn(user);
        when(userService.isAdmin(user)).thenReturn(false);
        when(chartApplicationService.getChart(9L, user, false)).thenReturn(chart);

        assertSame(chart, chartController.getChartVOById(9L, request).getData());
    }

    @Test
    void listEndpoints_shouldReturnPagesFromApplicationService() {
        ChartQueryRequest queryRequest = new ChartQueryRequest();
        Page<Chart> adminPage = new Page<>();
        Page<Chart> myPage = new Page<>();
        User user = loggedInUser();
        when(chartApplicationService.listCharts(queryRequest)).thenReturn(adminPage);
        when(userService.getLoginUser(request)).thenReturn(user);
        when(chartApplicationService.listMyCharts(queryRequest, user)).thenReturn(myPage);

        assertSame(adminPage, chartController.listChartByPage(queryRequest).getData());
        assertSame(myPage, chartController.listMyChartVOByPage(queryRequest, request).getData());
        // The deprecated "public" listing is now just the caller's own charts.
        assertSame(myPage, chartController.listChartVOByPage(queryRequest, request).getData());
    }

    @Test
    void editChart_shouldDeriveAdminFlagFromLoggedInUser() {
        User user = loggedInUser();
        ChartEditRequest editRequest = new ChartEditRequest();
        when(userService.getLoginUser(request)).thenReturn(user);
        when(userService.isAdmin(user)).thenReturn(false);
        when(chartApplicationService.editChart(editRequest, user, false)).thenReturn(true);

        assertTrue(chartController.editChart(editRequest, request).getData());
    }

    @Test
    void genChartByAI_shouldDelegateMultipartUpload() throws Exception {
        User user = loggedInUser();
        MultipartFile file = mock(MultipartFile.class);
        GenChartByAIRequest chartRequest = new GenChartByAIRequest();
        BiResponse expected = new BiResponse();
        when(userService.getLoginUser(request)).thenReturn(user);
        when(chartApplicationService.generateChart(file, chartRequest, user)).thenReturn(expected);

        assertSame(expected, chartController.genChartByAI(file, chartRequest, request).getData());
    }

    @Test
    void genChartByUploadedFile_withNullBody_shouldPassNullToken() throws Exception {
        User user = loggedInUser();
        when(userService.getLoginUser(request)).thenReturn(user);

        chartController.genChartByUploadedFile(null, request);

        verify(chartApplicationService).generateChart((String) null, null, user);
    }

    @Test
    void checkUploadedDataQuality_withNullBody_shouldPassNullToken() throws Exception {
        User user = loggedInUser();
        when(userService.getLoginUser(request)).thenReturn(user);

        chartController.checkUploadedDataQuality(null, request);

        verify(chartApplicationService).inspectData((String) null, user);
    }

    @Test
    void listJobsByChart_shouldUseRequestedChartIds() {
        User user = loggedInUser();
        JobIdsRequest jobIdsRequest = new JobIdsRequest();
        jobIdsRequest.setChartIds(List.of(1L, 2L));
        List<AnalysisJob> jobs = List.of(new AnalysisJob());
        when(userService.getLoginUser(request)).thenReturn(user);
        when(chartApplicationService.listJobs(List.of(1L, 2L), user)).thenReturn(jobs);

        assertSame(jobs, chartController.listJobsByChart(jobIdsRequest, request).getData());
    }

    @Test
    void listJobsByChart_withNullBody_shouldUseEmptyList() {
        User user = loggedInUser();
        when(userService.getLoginUser(request)).thenReturn(user);

        chartController.listJobsByChart(null, request);

        verify(chartApplicationService).listJobs(List.of(), user);
    }

    @Test
    void jobOperations_shouldDelegateForCurrentUser() {
        User user = loggedInUser();
        List<AnalysisJobEvent> events = List.of(new AnalysisJobEvent());
        when(userService.getLoginUser(request)).thenReturn(user);
        when(chartApplicationService.listJobEvents(3L, user)).thenReturn(events);
        when(chartApplicationService.retryJob(3L, user)).thenReturn(true);
        when(chartApplicationService.cancelJob(3L, user)).thenReturn(true);

        assertSame(events, chartController.listJobEvents(3L, request).getData());
        assertTrue(chartController.retryJob(3L, request).getData());
        assertTrue(chartController.cancelJob(3L, request).getData());
    }

    private User loggedInUser() {
        User user = new User();
        user.setId(101L);
        return user;
    }
}
