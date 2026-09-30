package com.zachary.BI.model.dto.chart;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.zachary.BI.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChartQueryRequest extends PageRequest implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String goal;

    private String chartType;

    private String name;

    private Long userId;

    private String keyWord;

    private List<String> chartTypes;

    private List<String> statuses;

    private String sourceFileType;

    // Inclusive
    private Long minSourceFileSize;

    // Inclusive
    private Long maxSourceFileSize;

    // Inclusive
    private LocalDate generatedFrom;

    // Inclusive
    private LocalDate generatedTo;

    private static final long serialVersionUID = 1L;
}
