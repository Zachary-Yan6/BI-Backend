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
public class ChartEditRequest implements Serializable {

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



    private static final long serialVersionUID = 1L;
}
