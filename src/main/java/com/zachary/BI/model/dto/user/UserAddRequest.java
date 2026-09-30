package com.zachary.BI.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class UserAddRequest implements Serializable {

    /**
     * Documentation.
     */
    private String userName;

    /**
     * Documentation.
     */
    private String userAccount;

    /**
     * Documentation.
     */
    private String userAvatar;

    /**
     * Documentation.
     */
    private String userRole;

    private static final long serialVersionUID = 1L;
}
