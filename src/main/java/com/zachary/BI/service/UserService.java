package com.zachary.BI.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.BI.model.dto.user.UserQueryRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.LoginUserVO;
import com.zachary.BI.model.vo.UserVO;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Documentation.
 *
 * @author Project contributors
 * @since 1.0
 */
public interface UserService extends IService<User> {

    /**
     * Documentation.
     *
     * @param userAccount Parameter description.
     * @param userPassword Parameter description.
     * @param checkPassword Parameter description.
     * @return Result.
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * Documentation.
     *
     * @param userAccount Parameter description.
     * @param userPassword Parameter description.
     * @param request
     * @return Result.
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);


    /**
     * Documentation.
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * Documentation.
     *
     * @param request
     * @return
     */
    User getLoginUserPermitNull(HttpServletRequest request);

    /**
     * Documentation.
     *
     * @param request
     * @return
     */
    boolean isAdmin(HttpServletRequest request);

    /**
     * Documentation.
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

    /**
     * Documentation.
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * Documentation.
     *
     * @return
     */
    LoginUserVO getLoginUserVO(User user);

    /**
     * Documentation.
     *
     * @param user
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * Documentation.
     *
     * @param userList
     * @return
     */
    List<UserVO> getUserVO(List<User> userList);

    /**
     * Documentation.
     *
     * @param userQueryRequest
     * @return
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

}
