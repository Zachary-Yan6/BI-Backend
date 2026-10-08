package com.zachary.BI.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zachary.BI.model.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * Locks the user's row until the current transaction ends, serialising work that must see that user's own
     * earlier writes, such as counting their active jobs before adding one.
     */
    @Select("SELECT id FROM user WHERE id = #{userId} FOR UPDATE")
    Long lockById(@Param("userId") long userId);
}



