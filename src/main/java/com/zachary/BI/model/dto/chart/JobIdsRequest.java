package com.zachary.BI.model.dto.chart;

import lombok.Data;
import java.util.List;

@Data
public class JobIdsRequest {
    private List<Long> chartIds;
}
