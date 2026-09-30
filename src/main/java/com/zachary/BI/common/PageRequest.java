package com.zachary.BI.common;

import com.zachary.BI.constant.CommonConstant;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class PageRequest {

    /**
     * Documentation.
     */
    private int current = 1;

    /**
     * Documentation.
     */
    private int pageSize = 10;

    /**
     * Documentation.
     */
    private String sortField;

    /**
     * Documentation.
     */
    private String sortOrder = CommonConstant.SORT_ORDER_ASC;
}
