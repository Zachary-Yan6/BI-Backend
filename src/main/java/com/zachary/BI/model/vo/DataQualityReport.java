package com.zachary.BI.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class DataQualityReport {

    /** Data rows inspected; when {@link #truncated} is true the file contained more. */
    private int totalRows;

    /** True when the file exceeded the read limits and only its beginning was inspected. */
    private boolean truncated;

    private int columnCount;

    private int duplicateRows;

    private String recommendation;

    private boolean confirmationRequired;

    private List<DataQualityColumn> columns;

    private List<DataQualityIssue> issues;
}
