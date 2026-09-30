package com.zachary.BI.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zachary.BI.common.BaseResponse;
import com.zachary.BI.common.DeleteRequest;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.model.dto.user.UserAddRequest;
import com.zachary.BI.model.dto.user.UserLoginRequest;
import com.zachary.BI.model.dto.user.UserQueryRequest;
import com.zachary.BI.model.dto.user.UserRegisterRequest;
import com.zachary.BI.model.dto.user.UserUpdateMyRequest;
import com.zachary.BI.model.dto.user.UserUpdateRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.LoginUserVO;
import com.zachary.BI.model.vo.UserVO;
import com.zachary.BI.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.DigestUtils;

import java.util.List;

import static com.zachary.BI.service.impl.UserServiceImpl.SALT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    // region register / login / logout

    @Test
    void userRegister_withNullBody_shouldReject() {
        assertParamsError(() -> userController.userRegister(null));
    }

    @Test
    void userRegister_withBlankField_shouldReturnNullWithoutCallingService() {
        UserRegisterRequest request = registerRequest("alice", "password1", " ");

        assertNull(userController.userRegister(request));
        verifyNoInteractions(userService);
    }

    @Test
    void userRegister_shouldReturnNewUserId() {
        when(userService.userRegister("alice", "password1", "password1")).thenReturn(10L);

        BaseResponse<Long> response = userController.userRegister(registerRequest("alice", "password1", "password1"));

        assertEquals(0, response.getCode());
        assertEquals(10L, response.getData());
    }

    @Test
    void userLogin_withNullBody_shouldReject() {
        assertParamsError(() -> userController.userLogin(null, httpRequest));
    }

    @Test
    void userLogin_withBlankPassword_shouldReject() {
        UserLoginRequest request = new UserLoginRequest();
        request.setUserAccount("alice");

        assertParamsError(() -> userController.userLogin(request, httpRequest));
        verifyNoInteractions(userService);
    }

    @Test
    void userLogin_shouldReturnLoginVO() {
        UserLoginRequest request = new UserLoginRequest();
        request.setUserAccount("alice");
        request.setUserPassword("password1");
        LoginUserVO vo = new LoginUserVO();
        when(userService.userLogin("alice", "password1", httpRequest)).thenReturn(vo);

        assertSame(vo, userController.userLogin(request, httpRequest).getData());
    }

    @Test
    void userLogout_withNullRequest_shouldReject() {
        assertParamsError(() -> userController.userLogout(null));
    }

    @Test
    void userLogout_shouldDelegate() {
        when(userService.userLogout(httpRequest)).thenReturn(true);

        assertTrue(userController.userLogout(httpRequest).getData());
    }

    @Test
    void getLoginUser_shouldReturnVOOfCurrentUser() {
        User user = user(1L);
        LoginUserVO vo = new LoginUserVO();
        when(userService.getLoginUser(httpRequest)).thenReturn(user);
        when(userService.getLoginUserVO(user)).thenReturn(vo);

        assertSame(vo, userController.getLoginUser(httpRequest).getData());
    }

    // endregion

    // region admin CRUD

    @Test
    void addUser_withNullBody_shouldReject() {
        assertParamsError(() -> userController.addUser(null, httpRequest));
    }

    @Test
    void addUser_shouldSaveWithDefaultEncryptedPassword() {
        UserAddRequest request = new UserAddRequest();
        request.setUserAccount("bob1");
        request.setUserName("Bob");
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(55L);
            return true;
        }).when(userService).save(any(User.class));

        BaseResponse<Long> response = userController.addUser(request, httpRequest);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userService).save(saved.capture());
        assertEquals(55L, response.getData());
        assertEquals("bob1", saved.getValue().getUserAccount());
        assertEquals(DigestUtils.md5DigestAsHex((SALT + "12345678").getBytes()), saved.getValue().getUserPassword());
    }

    @Test
    void addUser_whenSaveFails_shouldThrowOperationError() {
        when(userService.save(any(User.class))).thenReturn(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userController.addUser(new UserAddRequest(), httpRequest));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void deleteUser_withInvalidRequest_shouldReject() {
        DeleteRequest zeroId = new DeleteRequest();
        zeroId.setId(0L);

        assertParamsError(() -> userController.deleteUser(null, httpRequest));
        assertParamsError(() -> userController.deleteUser(zeroId, httpRequest));
    }

    @Test
    void deleteUser_shouldRemoveById() {
        DeleteRequest request = new DeleteRequest();
        request.setId(3L);
        when(userService.removeById(3L)).thenReturn(true);

        assertTrue(userController.deleteUser(request, httpRequest).getData());
    }

    @Test
    void updateUser_withMissingId_shouldReject() {
        assertParamsError(() -> userController.updateUser(null, httpRequest));
        assertParamsError(() -> userController.updateUser(new UserUpdateRequest(), httpRequest));
    }

    @Test
    void updateUser_shouldCopyFieldsAndUpdate() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(4L);
        request.setUserRole("admin");
        when(userService.updateById(any(User.class))).thenReturn(true);

        assertTrue(userController.updateUser(request, httpRequest).getData());

        ArgumentCaptor<User> updated = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(updated.capture());
        assertEquals(4L, updated.getValue().getId());
        assertEquals("admin", updated.getValue().getUserRole());
    }

    @Test
    void updateUser_whenUpdateFails_shouldThrowOperationError() {
        UserUpdateRequest request = new UserUpdateRequest();
        request.setId(4L);
        when(userService.updateById(any(User.class))).thenReturn(false);

        assertThrows(BusinessException.class, () -> userController.updateUser(request, httpRequest));
    }

    @Test
    void getUserById_withInvalidId_shouldReject() {
        assertParamsError(() -> userController.getUserById(0, httpRequest));
    }

    @Test
    void getUserById_whenMissing_shouldThrowNotFound() {
        when(userService.getById(8L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userController.getUserById(8L, httpRequest));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    @Test
    void getUserVOById_shouldConvertFetchedUser() {
        User user = user(8L);
        UserVO vo = new UserVO();
        when(userService.getById(8L)).thenReturn(user);
        when(userService.getUserVO(user)).thenReturn(vo);

        assertSame(vo, userController.getUserVOById(8L, httpRequest).getData());
    }

    // endregion

    // region paging

    @Test
    void listUserByPage_shouldQueryWithRequestedPage() {
        UserQueryRequest request = new UserQueryRequest();
        request.setCurrent(2);
        request.setPageSize(50);
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        Page<User> page = new Page<>(2, 50);
        when(userService.getQueryWrapper(request)).thenReturn(wrapper);
        when(userService.page(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        assertSame(page, userController.listUserByPage(request, httpRequest).getData());
    }

    @Test
    void listUserVOByPage_withInvalidRequest_shouldReject() {
        UserQueryRequest tooLarge = new UserQueryRequest();
        tooLarge.setPageSize(21);

        assertParamsError(() -> userController.listUserVOByPage(null, httpRequest));
        assertParamsError(() -> userController.listUserVOByPage(tooLarge, httpRequest));
    }

    @Test
    void listUserVOByPage_shouldMapRecordsToVOs() {
        UserQueryRequest request = new UserQueryRequest();
        request.setCurrent(1);
        request.setPageSize(10);
        Page<User> userPage = new Page<>(1, 10, 1);
        List<User> users = List.of(user(1L));
        userPage.setRecords(users);
        List<UserVO> vos = List.of(new UserVO());
        when(userService.getQueryWrapper(request)).thenReturn(new QueryWrapper<>());
        when(userService.page(any(Page.class), any(QueryWrapper.class))).thenReturn(userPage);
        when(userService.getUserVO(users)).thenReturn(vos);

        Page<UserVO> result = userController.listUserVOByPage(request, httpRequest).getData();

        assertEquals(1, result.getTotal());
        assertSame(vos, result.getRecords());
    }

    // endregion

    @Test
    void updateMyUser_withNullBody_shouldReject() {
        assertParamsError(() -> userController.updateMyUser(null, httpRequest));
    }

    @Test
    void updateMyUser_shouldAlwaysUseLoggedInUserId() {
        UserUpdateMyRequest request = new UserUpdateMyRequest();
        request.setUserName("New name");
        when(userService.getLoginUser(httpRequest)).thenReturn(user(77L));
        when(userService.updateById(any(User.class))).thenReturn(true);

        assertTrue(userController.updateMyUser(request, httpRequest).getData());

        ArgumentCaptor<User> updated = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(updated.capture());
        assertEquals(77L, updated.getValue().getId());
        assertEquals("New name", updated.getValue().getUserName());
    }

    @Test
    void updateMyUser_whenUpdateFails_shouldThrowOperationError() {
        when(userService.getLoginUser(httpRequest)).thenReturn(user(77L));
        when(userService.updateById(any(User.class))).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> userController.updateMyUser(new UserUpdateMyRequest(), httpRequest));
    }

    private static UserRegisterRequest registerRequest(String account, String password, String checkPassword) {
        UserRegisterRequest request = new UserRegisterRequest();
        request.setUserAccount(account);
        request.setUserPassword(password);
        request.setCheckPassword(checkPassword);
        return request;
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static void assertParamsError(org.junit.jupiter.api.function.Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
    }
}
