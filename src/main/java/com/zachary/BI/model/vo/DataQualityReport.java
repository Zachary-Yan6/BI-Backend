package com.zachary.BI.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class DataQualityReport {

    private int totalRows;

    private int columnCount;

    private int duplicateRows;

    private String recommendation;

    private boolean confirmationRequired;

    private List<DataQualityColumn> columns;

    private List<DataQualityIssue> issues;
}
