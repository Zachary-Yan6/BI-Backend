package com.zachary.BI.mapper;

import com.zachary.BI.model.entity.Chart;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;


/**
* @author 22091
* @description Application component.
* @createDate 2026-08-01 18:41:18
* @Entity com.zachary.BI.model.entity.Chart
*/
public interface ChartMapper extends BaseMapper<Chart> {

    /**
     * Copies a job's new status onto its chart, which is what the chart list shows and filters on.
     * Call it only inside the transaction that has just changed the job: the job row's lock then orders these
     * writes exactly like the job transitions, so an older status can never overwrite a newer one.
     *
     * @param execMessage shown with the status; null clears the previous one
     */
    @Update("UPDATE chart c JOIN analysis_job j ON j.chartId = c.id "
            + "SET c.status = #{status}, c.execMessage = #{execMessage} "
            + "WHERE j.id = #{jobId} AND c.isDelete = 0")
    int updateStatusForJob(@Param("jobId") long jobId, @Param("status") String status,
                           @Param("execMessage") String execMessage);
}
