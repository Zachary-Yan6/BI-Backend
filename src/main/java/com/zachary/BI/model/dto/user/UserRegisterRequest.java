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
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    private String userAccount;

    private String userPassword;

    private String checkPassword;
}
