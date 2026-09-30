package com.zachary.BI.model.dto.user;

import com.zachary.BI.common.PageRequest;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UserQueryRequest extends PageRequest implements Serializable {
    /**
     * id
     */
    private Long id;

    /**
     * Documentation.
     */
    private String unionId;

    /**
     * Documentation.
     */
    private String mpOpenId;

    /**
     * Documentation.
     */
    private String userName;

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
