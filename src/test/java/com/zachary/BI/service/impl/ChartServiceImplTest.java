package com.zachary.BI.service.impl;

import com.zachary.BI.mapper.ChartMapper;
import com.zachary.BI.model.entity.Chart;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChartServiceImplTest {

    @Test
    void getById_shouldDelegateToChartMapper() {
        ChartMapper chartMapper = mock(ChartMapper.class);
        Chart chart = new Chart();
        when(chartMapper.selectById(1L)).thenReturn(chart);
        ChartServiceImpl chartService = new ChartServiceImpl();
        ReflectionTestUtils.setField(chartService, "baseMapper", chartMapper);

        assertSame(chart, chartService.getById(1L));
    }
}
