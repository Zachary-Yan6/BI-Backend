package com.zachary.BI.model.dto.chart;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class ChartUpdateRequest implements Serializable {

    private Long id;

    /**
     * Documentation.
     */
    private String name;

    /**
     * Documentation.
     */
    private String goal;

    /**
     * Documentation.
     */
    private String chartData;

    /**
     * Documentation.
     */
    private String chartType;

    /**
     * Documentation.
     */
    private String genChart;

    /**
     * Documentation.
     */
    private String genResult;

    /**
     * Documentation.
     */
    private Date createTime;

    /**
     * Documentation.
     */
    private Date updateTime;


    private static final long serialVersionUID = 1L;
}
