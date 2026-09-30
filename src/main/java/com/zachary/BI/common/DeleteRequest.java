package com.zachary.BI.common;

import java.io.Serializable;
import lombok.Data;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
@Data
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    private static final long serialVersionUID = 1L;
}
