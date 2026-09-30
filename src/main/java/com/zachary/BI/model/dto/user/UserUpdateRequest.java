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
public class UserUpdateRequest implements Serializable {
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

    private static final long serialVersionUID = 1L;
}
