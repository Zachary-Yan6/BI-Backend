package com.zachary.BI.model.enums;

import java.util.Set;

public enum AnalysisJobStatusEnum {
    QUEUED("queued"), RUNNING("running"), RETRYING("retrying"), SUCCEEDED("succeeded"),
    FAILED("failed"), CANCELLED("cancelled");

    private final String value;

    AnalysisJobStatusEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isActive(String status) {
        return Set.of(QUEUED.value, RUNNING.value, RETRYING.value).contains(status);
    }
}
