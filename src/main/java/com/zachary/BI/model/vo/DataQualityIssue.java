package com.zachary.BI.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DataQualityIssue {

    private String severity;

    private String column;

    private String message;
}
