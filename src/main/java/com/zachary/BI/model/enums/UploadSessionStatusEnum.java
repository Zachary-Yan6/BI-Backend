package com.zachary.BI.model.enums;

public enum UploadSessionStatusEnum {

    CREATED("created"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    EXPIRED("expired"),
    ABORTED("aborted"),
    COMPLETING("completing");
    private final String value;

    UploadSessionStatusEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isActive(String status) {
        return CREATED.value.equals(status) || UPLOADING.value.equals(status);
    }
    public static boolean acceptsChunks(String status) {
        return CREATED.getValue().equals(status)
                || UPLOADING.getValue().equals(status);
    }
}