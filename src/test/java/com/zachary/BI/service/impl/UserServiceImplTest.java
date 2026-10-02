package com.zachary.BI.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.mapper.UserMapper;
import com.zachary.BI.model.dto.user.UserQueryRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.LoginUserVO;
import com.zachary.BI.model.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.util.List;

import static com.zachary.BI.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    private UserServiceImpl userService;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        // ServiceImpl resolves every CRUD call through this inherited field.
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        request = new MockHttpServletRequest();
    }

    // region register

    @ParameterizedTest
    @CsvSource({
            "'', password1, password1",
            "abc, password1, password1",
            "alice, short, short",
            "alice, password1, short",
            "alice, password1, password2"
    })
    void userRegister_withInvalidInput_shouldReject(String account, String password, String checkPassword) {
        assertBusinessError(ErrorCode.PARAMS_ERROR,
                () -> userService.userRegister(account, password, checkPassword));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void userRegister_whenAccountExists_shouldReject() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(ErrorCode.PARAMS_ERROR,
                () -> userService.userRegister("alice", "password1", "password1"));
    }

    @Test
    void userRegister_shouldStoreSaltedHashAndReturnId() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(99L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        long id = userService.userRegister("alice", "password1", "password1");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(saved.capture());
        assertEquals(99L, id);
        assertEquals("alice", saved.getValue().getUserAccount());
        assertEquals(DigestUtils.md5DigestAsHex((UserServiceImpl.SALT + "password1").getBytes()),
                saved.getValue().getUserPassword());
    }

    @Test
    void userRegister_whenInsertFails_shouldThrowSystemError() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenReturn(0);

        assertBusinessError(ErrorCode.SYSTEM_ERROR,
                () -> userService.userRegister("alice", "password1", "password1"));
    }

    // endregion

    // region login / session

    @ParameterizedTest
    @CsvSource({
            "'', password1",
            "abc, password1",
            "alice, short"
    })
    void userLogin_withInvalidInput_shouldReject(String account, String password) {
        assertBusinessError(ErrorCode.PARAMS_ERROR, () -> userService.userLogin(account, password, request));
    }

    @Test
    void userLogin_withWrongCredentials_shouldReject() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertBusinessError(ErrorCode.PARAMS_ERROR, () -> userService.userLogin("alice", "password1", request));
        assertNull(request.getSession().getAttribute(USER_LOGIN_STATE));
    }

    @Test
    void userLogin_shouldStoreUserInSessionAndReturnVO() {
        User user = user(5L, "user");
        user.setUserName("Alice");
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginUserVO vo = userService.userLogin("alice", "password1", request);

        // Only the identity goes into the session: no role to go stale, no password hash.
        User sessionUser = (User) request.getSession().getAttribute(USER_LOGIN_STATE);
        assertEquals(5L, sessionUser.getId());
        assertNull(sessionUser.getUserRole());
        assertNull(sessionUser.getUserPassword());
        assertEquals(5L, vo.getId());
        assertEquals("Alice", vo.getUserName());
    }

    @Test
    void userLogin_whenBanned_shouldRejectWithoutSession() {
        when(userMapper.selectOne(any())).thenReturn(user(5L, "ban"));

        assertBusinessError(ErrorCode.NO_AUTH_ERROR, () -> userService.userLogin("alice", "password1", request));
        assertNull(request.getSession().getAttribute(USER_LOGIN_STATE));
    }

    @Test
    void getLoginUser_whenBannedAfterLogin_shouldReject() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user(5L, "user"));
        when(userMapper.selectById(5L)).thenReturn(user(5L, "ban"));

        assertBusinessError(ErrorCode.NO_AUTH_ERROR, () -> userService.getLoginUser(request));
        assertNull(userService.getLoginUserPermitNull(request));
    }

    @Test
    void getLoginUser_withoutSessionUser_shouldThrowNotLogin() {
        assertBusinessError(ErrorCode.NOT_LOGIN_ERROR, () -> userService.getLoginUser(request));

        request.getSession().setAttribute(USER_LOGIN_STATE, new User());
        assertBusinessError(ErrorCode.NOT_LOGIN_ERROR, () -> userService.getLoginUser(request));
    }

    @Test
    void getLoginUser_whenUserWasDeleted_shouldThrowNotLogin() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user(5L, "user"));
        when(userMapper.selectById(5L)).thenReturn(null);

        assertBusinessError(ErrorCode.NOT_LOGIN_ERROR, () -> userService.getLoginUser(request));
    }

    @Test
    void getLoginUser_shouldReloadFreshUserFromDatabase() {
        User fresh = user(5L, "admin");
        request.getSession().setAttribute(USER_LOGIN_STATE, user(5L, "user"));
        when(userMapper.selectById(5L)).thenReturn(fresh);

        assertSame(fresh, userService.getLoginUser(request));
    }

    @Test
    void getLoginUserPermitNull_shouldReturnNullWhenNotSignedIn() {
        assertNull(userService.getLoginUserPermitNull(request));

        request.getSession().setAttribute(USER_LOGIN_STATE, new User());
        assertNull(userService.getLoginUserPermitNull(request));
    }

    @Test
    void getLoginUserPermitNull_shouldReloadUser() {
        User fresh = user(5L, "user");
        request.getSession().setAttribute(USER_LOGIN_STATE, user(5L, "user"));
        when(userMapper.selectById(5L)).thenReturn(fresh);

        assertSame(fresh, userService.getLoginUserPermitNull(request));
    }

    @Test
    void isAdmin_shouldCheckCurrentRoleFromDatabase() {
        assertFalse(userService.isAdmin(request));
        request.getSession().setAttribute(USER_LOGIN_STATE, user(1L, "admin"));
        // Demoted since login: the session snapshot still says admin, the database does not.
        when(userMapper.selectById(1L)).thenReturn(user(1L, "user"), user(1L, "admin"));
        assertFalse(userService.isAdmin(request));
        assertTrue(userService.isAdmin(request));

        assertFalse(userService.isAdmin((User) null));
        assertFalse(userService.isAdmin(user(1L, "user")));
    }

    @Test
    void userLogout_whenNotSignedIn_shouldThrow() {
        assertBusinessError(ErrorCode.OPERATION_ERROR, () -> userService.userLogout(request));
    }

    @Test
    void userLogout_shouldClearSession() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user(1L, "user"));

        assertTrue(userService.userLogout(request));
        assertNull(request.getSession().getAttribute(USER_LOGIN_STATE));
    }

    // endregion

    // region view objects and queries

    @Test
    void voConversions_shouldHandleNullAndCopyFields() {
        User user = user(3L, "user");
        user.setUserName("Carol");

        assertNull(userService.getLoginUserVO(null));
        assertNull(userService.getUserVO((User) null));
        assertEquals("Carol", userService.getLoginUserVO(user).getUserName());

        UserVO vo = userService.getUserVO(user);
        assertEquals(3L, vo.getId());
        assertEquals("Carol", vo.getUserName());
    }

    @Test
    void getUserVOList_shouldHandleEmptyAndMapEachUser() {
        assertTrue(userService.getUserVO((List<User>) null).isEmpty());
        assertTrue(userService.getUserVO(List.of()).isEmpty());

        List<UserVO> vos = userService.getUserVO(List.of(user(1L, "user"), user(2L, "admin")));

        assertEquals(List.of(1L, 2L), vos.stream().map(UserVO::getId).toList());
    }

    @Test
    void getQueryWrapper_withNullRequest_shouldReject() {
        assertBusinessError(ErrorCode.PARAMS_ERROR, () -> userService.getQueryWrapper(null));
    }

    @Test
    void getQueryWrapper_shouldIncludeOnlyProvidedFilters() {
        UserQueryRequest queryRequest = new UserQueryRequest();
        queryRequest.setId(1L);
        queryRequest.setUnionId("union");
        queryRequest.setMpOpenId("open");
        queryRequest.setUserRole("admin");
        queryRequest.setUserProfile("profile");
        queryRequest.setUserName("name");
        queryRequest.setSortField("createTime");
        queryRequest.setSortOrder("descend");

        String sql = userService.getQueryWrapper(queryRequest).getCustomSqlSegment();

        for (String column : List.of("id", "unionId", "mpOpenId", "userRole", "userProfile", "userName")) {
            assertTrue(sql.contains(column), () -> "missing " + column + " in " + sql);
        }
        assertTrue(sql.contains("ORDER BY createTime DESC"), sql);
    }

    @Test
    void getQueryWrapper_withEmptyRequest_shouldHaveNoConditions() {
        QueryWrapper<User> wrapper = userService.getQueryWrapper(new UserQueryRequest());

        // No filters; only the id tie-breaker that keeps pagination deterministic.
        assertEquals("ORDER BY id ASC", wrapper.getCustomSqlSegment().trim());
    }

    // endregion

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private static void assertBusinessError(ErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected.getCode(), exception.getCode());
    }
}
