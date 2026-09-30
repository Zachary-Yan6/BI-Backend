package com.zachary.BI.model.dto.chart;

import lombok.Data;

import java.io.Serializable;

/**
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class GenChartByAIRequest implements Serializable {


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
    private String chartType;

    /**
     * Completed resumable-upload identifier used by the JSON generation endpoint.
     */
    private String fileToken;

    private boolean qualityAcknowledged;

    private static final long serialVersionUID = 1L;
}
