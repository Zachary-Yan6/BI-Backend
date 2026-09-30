package com.zachary.BI.utils;

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
}
