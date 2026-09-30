package com.zachary.BI.model.vo;

import lombok.Data;

@Data
public class DataQualityColumn {

    private String name;

    private String inferredType;

    private int nullCount;

    private double nullRate;

    private Double minimum;

    private Double maximum;

    private Double average;

    private int dateFormatAnomalies;

    private int negativeValueCount;
}
