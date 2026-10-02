package com.zachary.BI.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SqlUtilsTest {

    @Test
    void acceptsColumnNames() {
        assertTrue(SqlUtils.validSortField("createTime"));
        assertTrue(SqlUtils.validSortField("user_id"));
    }

    @Test
    void applySort_shouldAlwaysEndWithIdAsTieBreaker() {
        assertEquals("ORDER BY createTime DESC,id DESC",
                orderBy("createTime", "descend"));
        assertEquals("ORDER BY name ASC,id ASC",
                orderBy("name", "ascend"));
    }

    @Test
    void applySort_withoutField_shouldStillOrderById() {
        assertEquals("ORDER BY id ASC", orderBy(null, "ascend"));
        assertEquals("ORDER BY id ASC", orderBy(" ", "ascend"));
    }

    @Test
    void applySort_shouldRejectFieldsOutsideTheAllowlist() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> orderBy("userPassword", "ascend"));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        assertThrows(BusinessException.class, () -> orderBy("id desc", "ascend"));
    }

    private static String orderBy(String sortField, String sortOrder) {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        SqlUtils.applySort(wrapper, sortField, sortOrder, Set.of("createTime", "name"));
        return wrapper.getSqlSegment().trim();
    }

    @Test
    void rejectsBlankAndUnsafeExpressions() {
        assertFalse(SqlUtils.validSortField(null));
        assertFalse(SqlUtils.validSortField(""));
        assertFalse(SqlUtils.validSortField("id desc"));
        assertFalse(SqlUtils.validSortField("id;drop table user"));
    }
}
