package com.zachary.BI.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.BI.model.entity.Chart;
import com.zachary.BI.service.ChartService;
import com.zachary.BI.mapper.ChartMapper;
import org.springframework.stereotype.Service;

/**
* @author 22091
* @description Application component.
* @createDate 2026-08-01 18:41:18
*/
@Service
public class ChartServiceImpl extends ServiceImpl<ChartMapper, Chart>
    implements ChartService{

}




