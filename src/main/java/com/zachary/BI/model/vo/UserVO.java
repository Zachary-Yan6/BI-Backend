package com.zachary.BI.model.vo;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class UserVO implements Serializable {

    /**
     * id
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

    private static final long serialVersionUID = 1L;
}
