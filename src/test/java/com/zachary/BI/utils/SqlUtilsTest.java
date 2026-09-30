package com.zachary.BI.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlUtilsTest {

    @Test
    void acceptsColumnNames() {
        assertTrue(SqlUtils.validSortField("createTime"));
        assertTrue(SqlUtils.validSortField("user_id"));
    }

    @Test
    void rejectsBlankAndUnsafeExpressions() {
        assertFalse(SqlUtils.validSortField(null));
        assertFalse(SqlUtils.validSortField(""));
        assertFalse(SqlUtils.validSortField("id desc"));
        assertFalse(SqlUtils.validSortField("id;drop table user"));
    }
}
