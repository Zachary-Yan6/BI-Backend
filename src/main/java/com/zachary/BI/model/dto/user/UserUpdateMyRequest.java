package com.zachary.BI.model.dto.user;

import java.io.Serializable;
import lombok.Data;


@Data
public class UserUpdateMyRequest implements Serializable {

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

    private static final long serialVersionUID = 1L;
}
