package com.zachary.BI.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.constant.CommonConstant;
import com.zachary.BI.exception.ThrowUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
public class SqlUtils {

    /**
     * Documentation.
     *
     * @param sortField
     * @return
     */
    public static boolean validSortField(String sortField) {
        return sortField != null && sortField.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * Applies a deterministic order for paginated queries: the requested column, then the primary key.
     * <p>
     * MySQL may return rows with equal ORDER BY values in any order, and that order can differ between the
     * LIMIT queries of consecutive pages, so rows would repeat on one page and vanish from another. The id
     * tie-breaker makes every position unique. Without a requested column the order is the id alone, which is
     * what callers effectively received before.
     *
     * @param allowedFields columns callers may sort by; anything else is rejected rather than reaching SQL
     */
    public static void applySort(QueryWrapper<?> wrapper, String sortField, String sortOrder,
                                 Set<String> allowedFields) {
        // Same rule the callers used before: only "ascend" is ascending.
        boolean ascending = CommonConstant.SORT_ORDER_ASC.equals(sortOrder);
        if (StringUtils.isNotBlank(sortField)) {
            ThrowUtils.throwIf(!allowedFields.contains(sortField), ErrorCode.PARAMS_ERROR,
                    "Sorting by '" + sortField + "' is not supported.");
            wrapper.orderBy(true, ascending, sortField);
        }
        wrapper.orderBy(true, ascending, "id");
    }
}
