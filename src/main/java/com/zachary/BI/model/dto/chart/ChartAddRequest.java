package com.zachary.BI.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class ChartAddRequest implements Serializable {

    /**
     * Documentation.
     */
    private String goal;

    /**
     * Documentation.
     */
    private String name;

    /**
     * Documentation.
     */
    private String chartData;

    /**
     * Documentation.
     */
    private String chartType;


    private static final long serialVersionUID = 1L;
}
