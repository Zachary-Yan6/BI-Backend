package com.zachary.BI.service.impl;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.service.AnalysisJobService;
import com.zachary.BI.service.ChartService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisCompletionServiceImplTest {

    @Mock
    private ChartService chartService;

    @Mock
    private AnalysisJobService analysisJobService;

    @InjectMocks
    private AnalysisCompletionServiceImpl analysisCompletionService;

    @Test
    void persistSuccess_shouldStoreResultAndMarkJobSucceeded() {
        when(chartService.updateById(any(Chart.class))).thenReturn(true);

        analysisCompletionService.persistSuccess(1L, 2L, "{\"chart\":1}", "Trend is rising");

        ArgumentCaptor<Chart> captor = ArgumentCaptor.forClass(Chart.class);
        verify(chartService).updateById(captor.capture());
        Chart chart = captor.getValue();
        assertEquals(2L, chart.getId());
        assertEquals("succeeded", chart.getStatus());
        assertEquals("{\"chart\":1}", chart.getGenChart());
        assertEquals("Trend is rising", chart.getGenResult());
        assertNotNull(chart.getGeneratedAt());
        verify(analysisJobService).succeed(1L);
    }

    @Test
    void persistSuccess_whenChartUpdateFails_shouldNotTouchJob() {
        when(chartService.updateById(any(Chart.class))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> analysisCompletionService.persistSuccess(1L, 2L, "chart", "result"));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(analysisJobService);
    }
}
