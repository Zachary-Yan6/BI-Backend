package com.zachary.BI.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@TableName(value = "user")
@Data
public class User implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Documentation.
     */
    private String userAccount;

    /**
     * Documentation.
     */
    private String userPassword;

    /**
     * Documentation.
     */
    private String userName;

    /**
     * Documentation.
     */
    private String userAvatar;

    /**
     * Documentation.
     */
    private String userRole;

    /**
     * Documentation.
     */
    private Date createTime;

    /**
     * Documentation.
     */
    private Date updateTime;

    /**
     * Documentation.
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
