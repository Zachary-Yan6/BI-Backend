package com.zachary.BI.controller;

import com.zachary.BI.model.dto.upload.CreateUploadSessionRequest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.CreateUploadSessionResponse;
import com.zachary.BI.model.vo.UploadChunkResponse;
import com.zachary.BI.model.vo.UploadCompletionResponse;
import com.zachary.BI.model.vo.UploadSessionStatusResponse;
import com.zachary.BI.service.ResumableUploadService;
import com.zachary.BI.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {

    @Mock
    private ResumableUploadService resumableUploadService;

    @Mock
    private UserService userService;

    @InjectMocks
    private UploadController uploadController;

    private MockHttpServletRequest httpRequest;
    private User user;

    @BeforeEach
    void setUp() {
        httpRequest = new MockHttpServletRequest();
        user = new User();
        user.setId(42L);
        when(userService.getLoginUser(httpRequest)).thenReturn(user);
    }

    @Test
    void createSession_shouldDelegateForLoggedInUser() {
        CreateUploadSessionRequest request = new CreateUploadSessionRequest();
        CreateUploadSessionResponse expected = new CreateUploadSessionResponse();
        when(resumableUploadService.createSession(request, user)).thenReturn(expected);

        assertSame(expected, uploadController.createSession(request, httpRequest).getData());
    }

    @Test
    void getSessionStatus_shouldDelegateForLoggedInUser() {
        UploadSessionStatusResponse expected = new UploadSessionStatusResponse();
        when(resumableUploadService.getSessionStatus("upload-1", user)).thenReturn(expected);

        assertSame(expected, uploadController.getSessionStatus("upload-1", httpRequest).getData());
    }

    @Test
    void uploadChunk_shouldForwardBodyStreamAndContentLength() throws Exception {
        byte[] body = {1, 2, 3, 4};
        httpRequest.setContent(body);
        UploadChunkResponse expected = new UploadChunkResponse();
        when(resumableUploadService.uploadChunk(eq("upload-1"), eq(0), eq("bytes 0-3/4"), eq("sha"),
                eq(4L), any(InputStream.class), eq(user))).thenReturn(expected);

        UploadChunkResponse actual = uploadController
                .uploadChunk("upload-1", 0, "bytes 0-3/4", "sha", httpRequest)
                .getData();

        assertSame(expected, actual);
        verify(resumableUploadService).uploadChunk(eq("upload-1"), eq(0), eq("bytes 0-3/4"), eq("sha"),
                eq(4L), any(InputStream.class), eq(user));
    }

    @Test
    void completeUpload_shouldDelegateForLoggedInUser() throws Exception {
        UploadCompletionResponse expected = new UploadCompletionResponse();
        expected.setFileToken("upload-1");
        when(resumableUploadService.completeUpload("upload-1", user)).thenReturn(expected);

        assertEquals("upload-1", uploadController.completeUpload("upload-1", httpRequest).getData().getFileToken());
    }
}
