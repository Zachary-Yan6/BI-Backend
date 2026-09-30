package com.zachary.BI.model.dto.upload;

import lombok.Data;

import java.io.Serializable;

/**
 * References a completed private upload without exposing its physical storage path.
 */
@Data
public class FileTokenRequest implements Serializable {

    private String fileToken;

    private static final long serialVersionUID = 1L;
}
