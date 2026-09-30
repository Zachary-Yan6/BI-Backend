package com.zachary.BI.model.vo;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 **/
@Data
public class LoginUserVO implements Serializable {

    /**
     * Documentation.
     */
    private Long id;

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
    private String userProfile;

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

    private static final long serialVersionUID = 1L;
}
