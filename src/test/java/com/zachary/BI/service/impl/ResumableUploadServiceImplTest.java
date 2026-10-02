package com.zachary.BI.service.impl;

import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.config.UploadProperties;
import com.zachary.BI.exception.BusinessException;
import com.zachary.BI.mapper.UploadChunkMapper;
import com.zachary.BI.mapper.UploadSessionMapper;
import com.zachary.BI.model.dto.upload.CompletedUploadFile;
import com.zachary.BI.model.dto.upload.CreateUploadSessionRequest;
import com.zachary.BI.model.entity.UploadChunk;
import com.zachary.BI.model.entity.UploadSession;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.model.vo.CreateUploadSessionResponse;
import com.zachary.BI.model.vo.UploadChunkResponse;
import com.zachary.BI.model.vo.UploadCompletionResponse;
import com.zachary.BI.model.vo.UploadSessionStatusResponse;
import com.zachary.BI.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumableUploadServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long SESSION_ID = 1L;
    private static final String UPLOAD_ID = "upload-1";
    private static final String STORAGE_KEY = "storage-key";
    private static final int CHUNK_SIZE = 4;
    /** Ten bytes split into chunks "abcd", "efgh" and "ij". */
    private static final byte[] FILE_BYTES = "abcdefghij".getBytes(StandardCharsets.US_ASCII);

    @Mock
    private UploadSessionMapper uploadSessionMapper;

    @Mock
    private UploadChunkMapper uploadChunkMapper;

    @InjectMocks
    private ResumableUploadServiceImpl uploadService;

    @TempDir
    Path stagingRoot;

    private UploadProperties uploadProperties;
    private User user;

    @BeforeAll
    static void initLambdaCache() {
        MybatisPlusTestSupport.initTableInfo(UploadSession.class, UploadChunk.class);
    }

    @BeforeEach
    void setUp() {
        uploadProperties = new UploadProperties();
        uploadProperties.setStagingDirectory(stagingRoot.toString());
        uploadProperties.setMaxFileSizeBytes(1_000);
        uploadProperties.setChunkSizeBytes(CHUNK_SIZE);
        uploadProperties.setSessionTtlHours(24);
        ReflectionTestUtils.setField(uploadService, "uploadProperties", uploadProperties);

        user = new User();
        user.setId(USER_ID);
    }

    // region createSession

    @Nested
    class CreateSession {

        @Test
        void shouldRejectInvalidRequests() {
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(null, user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(request(" ", 10L), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadService.createSession(request("a".repeat(509) + ".csv", 10L), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(request("a.csv", null), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(request("a.csv", 0L), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(request("a.csv", 1_001L), user));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.createSession(request("a.pdf", 10L), user));
            verifyNoInteractions(uploadSessionMapper);
        }

        @Test
        void shouldRejectFilesThatNeedTooManyChunks() {
            uploadProperties.setMaxFileSizeBytes(Long.MAX_VALUE);
            uploadProperties.setChunkSizeBytes(1);

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadService.createSession(request("a.csv", Integer.MAX_VALUE + 1L), user));
        }

        @Test
        void shouldCreateStagingDirectoryAndPersistSession() throws Exception {
            CreateUploadSessionRequest request = request("  Report.XLSX ", 10L);
            request.setContentType("c".repeat(200));

            CreateUploadSessionResponse response = uploadService.createSession(request, user);

            ArgumentCaptor<UploadSession> captor = ArgumentCaptor.forClass(UploadSession.class);
            verify(uploadSessionMapper).insert(captor.capture());
            UploadSession session = captor.getValue();
            assertEquals(USER_ID, session.getUserId());
            assertEquals("Report.XLSX", session.getOriginalFileName());
            assertEquals("xlsx", session.getSourceFileType());
            assertEquals(128, session.getContentType().length());
            assertEquals(10L, session.getTotalSize());
            assertEquals(CHUNK_SIZE, session.getChunkSize());
            assertEquals(3, session.getTotalChunks());
            assertEquals("created", session.getStatus());
            assertTrue(Files.isDirectory(stagingRoot.resolve(session.getStorageKey())));

            assertEquals(session.getUploadId(), response.getUploadId());
            assertEquals(CHUNK_SIZE, response.getChunkSize());
            assertEquals(3, response.getTotalChunks());
            assertTrue(response.getUploadedChunkIndexes().isEmpty());
            assertTrue(response.getExpiresAt().after(new Date()));
        }

        @Test
        void whenStagingDirectoryCannotBeCreated_shouldThrowSystemError() throws Exception {
            Path notADirectory = Files.writeString(stagingRoot.resolve("file"), "x");
            uploadProperties.setStagingDirectory(notADirectory.toString());

            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> uploadService.createSession(request("a.csv", 10L), user));
            verifyNoInteractions(uploadSessionMapper);
        }

        @Test
        void whenInsertFails_shouldRemoveEmptyStagingDirectory() throws Exception {
            IllegalStateException failure = new IllegalStateException("db down");
            when(uploadSessionMapper.insert(any(UploadSession.class))).thenThrow(failure);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> uploadService.createSession(request("a.csv", 10L), user));

            assertSame(failure, thrown);
            assertEquals(0, countEntries(stagingRoot));
        }

        @Test
        void whenInsertFailsAndCleanupFails_shouldStillRethrowOriginalFailure() {
            IllegalStateException failure = new IllegalStateException("db down");
            doAnswer(invocation -> {
                // A non-empty directory cannot be removed, which forces the cleanup path to fail.
                UploadSession session = invocation.getArgument(0);
                Files.writeString(stagingRoot.resolve(session.getStorageKey()).resolve("blocker"), "x");
                throw failure;
            }).when(uploadSessionMapper).insert(any(UploadSession.class));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> uploadService.createSession(request("a.csv", 10L), user));

            assertSame(failure, thrown);
        }

        private CreateUploadSessionRequest request(String fileName, Long totalSize) {
            CreateUploadSessionRequest request = new CreateUploadSessionRequest();
            request.setFileName(fileName);
            request.setTotalSize(totalSize);
            return request;
        }
    }

    // endregion

    // region getSessionStatus

    @Nested
    class GetSessionStatus {

        @Test
        void whenSessionMissing_shouldThrowNotFound() {
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> uploadService.getSessionStatus(UPLOAD_ID, user));
        }

        @Test
        void whenActiveSessionExpired_shouldMarkExpired() {
            UploadSession session = session("uploading");
            session.setExpiresAt(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            when(uploadSessionMapper.update(isNull(), any())).thenReturn(1);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 1, 2));

            UploadSessionStatusResponse response = uploadService.getSessionStatus(UPLOAD_ID, user);

            assertEquals("expired", response.getStatus());
            assertFalse(response.isReadyToComplete());
            verify(uploadSessionMapper, never()).updateById(any(UploadSession.class));
        }

        @Test
        void whenExpiryLosesRaceToAnotherChange_shouldReportTheCurrentRow() {
            UploadSession stale = session("uploading");
            stale.setExpiresAt(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)));
            UploadSession current = session("aborted");
            current.setExpiresAt(stale.getExpiresAt());
            when(uploadSessionMapper.selectOne(any())).thenReturn(stale);
            when(uploadSessionMapper.update(isNull(), any())).thenReturn(0);
            when(uploadSessionMapper.selectById(SESSION_ID)).thenReturn(current);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0));

            assertEquals("aborted", uploadService.getSessionStatus(UPLOAD_ID, user).getStatus());
        }

        @Test
        void whenAllChunksUploaded_shouldBeReadyToComplete() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 1, 2));

            UploadSessionStatusResponse response = uploadService.getSessionStatus(UPLOAD_ID, user);

            assertEquals(UPLOAD_ID, response.getUploadId());
            assertEquals(List.of(0, 1, 2), response.getUploadedChunkIndexes());
            assertEquals(3, response.getTotalChunks());
            assertEquals(CHUNK_SIZE, response.getChunkSize());
            assertNotNull(response.getExpiresAt());
            assertTrue(response.isReadyToComplete());
            verify(uploadSessionMapper, never()).update(any(), any());
        }

        @Test
        void completedOrPartialSessions_shouldNotBeReadyToComplete() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("completed"), session("created"));
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 1, 2), chunkRecords(0));

            assertFalse(uploadService.getSessionStatus(UPLOAD_ID, user).isReadyToComplete());
            assertFalse(uploadService.getSessionStatus(UPLOAD_ID, user).isReadyToComplete());
        }
    }

    // endregion

    // region uploadChunk

    @Nested
    class UploadChunkTests {

        @Test
        void whenSessionMissing_shouldThrowNotFound() {
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
        }

        @Test
        void whenSessionNotAcceptingChunks_shouldReject() {
            UploadSession expired = session("uploading");
            expired.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("completed"), expired);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
        }

        @Test
        void shouldValidateIndexChecksumAndRange() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("created"));
            byte[] data = chunk(0);
            String sha = sha256(data);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> upload(-1, "bytes 0-3/10", data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> upload(3, "bytes 0-3/10", data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 0-3/10", null, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 0-3/10", "not-a-hash", 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, null, sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "0-3/10", sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadWith(0, "bytes 99999999999999999999-3/10", sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 0-3/11", sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 1-3/10", sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 0-2/10", sha, 4, data));
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadWith(0, "bytes 0-3/10", sha, 3, data));
            verifyNoInteractions(uploadChunkMapper);
        }

        @Test
        void shouldStoreVerifiedChunkAndMoveSessionToUploading() throws Exception {
            UploadSession session = session("created");
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            when(uploadSessionMapper.update(isNull(), any())).thenReturn(1);
            when(uploadChunkMapper.selectCount(any())).thenReturn(1L);

            UploadChunkResponse response = upload(0, "bytes 0-3/10", chunk(0));

            assertEquals(UPLOAD_ID, response.getUploadId());
            assertEquals(0, response.getChunkIndex());
            assertFalse(response.isAlreadyUploaded());
            assertEquals(1, response.getUploadedChunks());
            assertEquals(3, response.getTotalChunks());
            assertFalse(response.isReadyToComplete());

            ArgumentCaptor<UploadChunk> captor = ArgumentCaptor.forClass(UploadChunk.class);
            verify(uploadChunkMapper).insert(captor.capture());
            assertEquals(SESSION_ID, captor.getValue().getUploadSessionId());
            assertEquals(0, captor.getValue().getChunkIndex());
            assertEquals(4, captor.getValue().getChunkSize());
            assertEquals(sha256(chunk(0)), captor.getValue().getSha256());

            assertArrayEquals(chunk(0), Files.readAllBytes(chunkFile(0, chunk(0))));
            assertEquals(1, countEntries(sessionDirectory()));
            assertEquals("uploading", session.getStatus());
            verify(uploadSessionMapper).update(isNull(), any());
            verify(uploadSessionMapper, never()).updateById(any(UploadSession.class));
        }

        @Test
        void whenSessionMovedOnDuringUpload_shouldStoreChunkWithoutRevertingStatus() throws Exception {
            UploadSession session = session("created");
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            // Another request changed the row after this one read it, so the conditional update matches nothing.
            when(uploadSessionMapper.update(isNull(), any())).thenReturn(0);
            when(uploadChunkMapper.selectCount(any())).thenReturn(1L);

            upload(0, "bytes 0-3/10", chunk(0));

            verify(uploadChunkMapper).insert(any(UploadChunk.class));
            assertEquals("created", session.getStatus(), "the stale snapshot is not presented as the new state");
            verify(uploadSessionMapper, never()).updateById(any(UploadSession.class));
        }

        @Test
        void lastChunk_shouldBeShorterAndCompleteTheSet() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectCount(any())).thenReturn(3L);

            UploadChunkResponse response = upload(2, "bytes 8-9/10", chunk(2));

            assertTrue(response.isReadyToComplete());
            assertTrue(Files.isRegularFile(chunkFile(2, chunk(2))));
            verify(uploadSessionMapper, never()).update(any(), any());
        }

        @Test
        void retryingIdenticalChunk_shouldBeIdempotent() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectOne(any())).thenReturn(chunkRecord(0, sha256(chunk(0)).toUpperCase(), 4));
            when(uploadChunkMapper.selectCount(any())).thenReturn(1L);

            UploadChunkResponse response = upload(0, "bytes 0-3/10", chunk(0));

            assertTrue(response.isAlreadyUploaded());
            verify(uploadChunkMapper, never()).insert(any(UploadChunk.class));
            assertFalse(Files.exists(sessionDirectory()));
        }

        @Test
        void conflictingExistingChunk_shouldBeRejected() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectOne(any())).thenReturn(
                    chunkRecord(0, sha256(chunk(1)), 4),
                    chunkRecord(0, sha256(chunk(0)), 3));

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
        }

        @Test
        void storageKeyOutsideStagingRoot_shouldBeRejected() {
            UploadSession session = session("uploading");
            session.setStorageKey("../escape");
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);

            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
        }

        @Test
        void bodyLargerThanDeclared_shouldBeRejectedAndTempFileRemoved() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadWith(0, "bytes 0-3/10", sha256(chunk(0)), 4, "abcde".getBytes()));

            assertEquals(0, countEntries(sessionDirectory()));
        }

        @Test
        void bodyShorterThanDeclared_shouldBeRejectedAndTempFileRemoved() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadWith(0, "bytes 0-3/10", sha256(chunk(0)), 4, "abc".getBytes()));
            verify(uploadChunkMapper, never()).insert(any(UploadChunk.class));
            assertEquals(0, countEntries(sessionDirectory()));
        }

        @Test
        void checksumMismatch_shouldBeRejectedAndTempFileRemoved() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));

            assertBusinessError(ErrorCode.PARAMS_ERROR,
                    () -> uploadWith(0, "bytes 0-3/10", sha256(chunk(1)), 4, chunk(0)));
            verify(uploadChunkMapper, never()).insert(any(UploadChunk.class));
            assertEquals(0, countEntries(sessionDirectory()));
        }

        @Test
        void streamFailure_shouldRemoveTempFileAndRethrow() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            InputStream broken = new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("connection reset");
                }
            };

            IOException thrown = assertThrows(IOException.class, () -> uploadService.uploadChunk(
                    UPLOAD_ID, 0, "bytes 0-3/10", sha256(chunk(0)), 4, broken, user));

            assertEquals("connection reset", thrown.getMessage());
            assertEquals(0, countEntries(sessionDirectory()));
        }

        @Test
        void concurrentIdenticalChunk_shouldReturnWinnerAndDropDuplicateFile() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectOne(any())).thenReturn(null, chunkRecord(0, sha256(chunk(0)), 4));
            when(uploadChunkMapper.insert(any(UploadChunk.class))).thenThrow(new DuplicateKeyException("dup"));
            when(uploadChunkMapper.selectCount(any())).thenReturn(1L);

            UploadChunkResponse response = upload(0, "bytes 0-3/10", chunk(0));

            assertTrue(response.isAlreadyUploaded());
            assertFalse(Files.exists(chunkFile(0, chunk(0))));
        }

        @Test
        void concurrentConflictingChunk_shouldBeRejected() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.selectOne(any())).thenReturn(null, null);
            when(uploadChunkMapper.insert(any(UploadChunk.class))).thenThrow(new DuplicateKeyException("dup"));

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> upload(0, "bytes 0-3/10", chunk(0)));
        }

        @Test
        void insertFailure_shouldRemoveStoredChunkAndRethrow() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            when(uploadChunkMapper.insert(any(UploadChunk.class))).thenThrow(new IllegalStateException("db down"));

            assertThrows(IllegalStateException.class, () -> upload(0, "bytes 0-3/10", chunk(0)));

            assertEquals(0, countEntries(sessionDirectory()));
        }

        private UploadChunkResponse upload(int index, String range, byte[] data) throws IOException {
            return uploadWith(index, range, sha256(data), data.length, data);
        }

        private UploadChunkResponse uploadWith(int index, String range, String sha, long contentLength, byte[] body)
                throws IOException {
            return uploadService.uploadChunk(UPLOAD_ID, index, range, sha, contentLength,
                    new ByteArrayInputStream(body), user);
        }
    }

    // endregion

    // region completeUpload

    @Nested
    class CompleteUpload {

        @Test
        void whenSessionMissing_shouldThrowNotFound() {
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenAlreadyCompleted_shouldReturnOriginalResultWithoutClaiming() throws Exception {
            UploadSession session = completedSession();
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);

            UploadCompletionResponse response = uploadService.completeUpload(UPLOAD_ID, user);

            assertCompletion(response, session.getWholeFileSha256());
            verify(uploadSessionMapper, never())
                    .claimCompletion(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void whenSessionCannotAcceptCompletion_shouldReject() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("expired"));

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenClaimLostToFinishedRequest_shouldReturnItsResult() throws Exception {
            UploadSession completed = completedSession();
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"), completed);
            stubClaim(0);

            assertCompletion(uploadService.completeUpload(UPLOAD_ID, user), completed.getWholeFileSha256());
        }

        @Test
        void whenClaimLostToInFlightRequest_shouldReject() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"), session("completing"));
            stubClaim(0);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void shouldMergeChunksInOrderAndDeleteParts() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            stubStoredChunks(0, 1, 2);
            String wholeSha = sha256(FILE_BYTES);
            String expectedKey = STORAGE_KEY + "/completed-" + wholeSha + ".data";
            when(uploadSessionMapper.markCompleted(eq(SESSION_ID), eq(USER_ID), anyString(), eq("completed"),
                    eq("completing"), eq(expectedKey), eq(wholeSha))).thenReturn(1);

            UploadCompletionResponse response = uploadService.completeUpload(UPLOAD_ID, user);

            assertCompletion(response, wholeSha);
            assertNotNull(response.getCompletedAt());
            assertArrayEquals(FILE_BYTES, Files.readAllBytes(stagingRoot.resolve(expectedKey)));
            // Only the merged file remains: parts and temporary files are cleaned up.
            assertEquals(1, countEntries(sessionDirectory()));
            verify(uploadSessionMapper, never())
                    .releaseCompletion(anyLong(), anyLong(), anyString(), anyString(), anyString());
        }

        @Test
        void whenCompletionLeaseLost_shouldReleaseClaimAndKeepChunks() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            stubStoredChunks(0, 1, 2);
            when(uploadSessionMapper.markCompleted(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                    anyString(), anyString())).thenReturn(0);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));

            verifyReleased();
            assertTrue(Files.isRegularFile(chunkFile(0, chunk(0))));
        }

        @Test
        void whenChunksAreMissing_shouldReleaseClaim() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 1));

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
            verifyReleased();
        }

        @Test
        void whenChunkSequenceHasGap_shouldReject() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            writeChunkFiles(0, 1, 2);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 2, 2));

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenChunkSizeMetadataIsWrong_shouldReject() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            writeChunkFiles(0, 1, 2);
            List<UploadChunk> chunks = chunkRecords(0, 1, 2);
            chunks.get(1).setChunkSize(3);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunks);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenStoredChecksumIsInvalid_shouldReject() {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            List<UploadChunk> chunks = chunkRecords(0, 1, 2);
            chunks.getFirst().setSha256("../../evil");
            when(uploadChunkMapper.selectList(any())).thenReturn(chunks);

            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenStorageKeyEscapesStagingRoot_shouldReject() {
            UploadSession session = session("uploading");
            session.setStorageKey("../escape");
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            stubClaim(1);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(0, 1, 2));

            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenChunkFileIsMissing_shouldReject() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            stubStoredChunks(0, 1, 2);
            Files.delete(chunkFile(1, chunk(1)));

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenChunkFileHasWrongSize_shouldReject() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            stubStoredChunks(0, 1, 2);
            Files.write(chunkFile(1, chunk(1)), "toolong".getBytes());

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
        }

        @Test
        void whenChunkContentWasTampered_shouldRejectAndRemoveTempFile() throws Exception {
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"));
            stubClaim(1);
            stubStoredChunks(0, 1, 2);
            Files.write(chunkFile(1, chunk(1)), "XXXX".getBytes());

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));

            verifyReleased();
            try (Stream<Path> files = Files.list(sessionDirectory())) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        }

        @Test
        void whenMergedSizeDiffersFromSession_shouldReject() throws Exception {
            // Metadata claims one chunk for a ten-byte file, so the merged output is too short.
            UploadSession session = session("uploading");
            session.setTotalChunks(1);
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            stubClaim(1);
            stubStoredChunks(0);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.completeUpload(UPLOAD_ID, user));
            verifyReleased();
        }

        private void stubClaim(int result) {
            when(uploadSessionMapper.claimCompletion(eq(SESSION_ID), eq(USER_ID), anyString(), eq("completing"),
                    eq("created"), eq("uploading"))).thenReturn(result);
        }

        private void stubStoredChunks(int... indexes) throws IOException {
            writeChunkFiles(indexes);
            when(uploadChunkMapper.selectList(any())).thenReturn(chunkRecords(indexes));
        }

        private void writeChunkFiles(int... indexes) throws IOException {
            Files.createDirectories(sessionDirectory());
            for (int index : indexes) {
                Files.write(chunkFile(index, chunk(index)), chunk(index));
            }
        }

        private void verifyReleased() {
            verify(uploadSessionMapper).releaseCompletion(eq(SESSION_ID), eq(USER_ID), anyString(),
                    eq("uploading"), eq("completing"));
        }

        private void assertCompletion(UploadCompletionResponse response, String sha) {
            assertEquals(UPLOAD_ID, response.getFileToken());
            assertEquals("data.csv", response.getOriginalFileName());
            assertEquals("csv", response.getSourceFileType());
            assertEquals(10L, response.getTotalSize());
            assertEquals(sha, response.getWholeFileSha256());
        }
    }

    // endregion

    // region resolveCompletedUpload

    @Nested
    class ResolveCompletedUpload {

        @Test
        void shouldRejectBlankTokenAndUnknownSession() {
            assertBusinessError(ErrorCode.PARAMS_ERROR, () -> uploadService.resolveCompletedUpload("  ", user));
            assertBusinessError(ErrorCode.NOT_FOUND_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
        }

        @Test
        void shouldRejectIncompleteOrInvalidMetadata() {
            UploadSession missingKey = completedSession();
            missingKey.setCompletedStorageKey(" ");
            UploadSession missingHash = completedSession();
            missingHash.setWholeFileSha256(null);
            when(uploadSessionMapper.selectOne(any())).thenReturn(session("uploading"), missingKey, missingHash);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
        }

        @Test
        void shouldRejectPathsOutsideStagingRoot() {
            UploadSession session = completedSession();
            session.setCompletedStorageKey("../../outside.data");
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);

            assertBusinessError(ErrorCode.SYSTEM_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
        }

        @Test
        void shouldRejectMissingOrResizedFiles() throws Exception {
            UploadSession session = completedSession();
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);

            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));

            writeCompletedFile(session, "short".getBytes());
            assertBusinessError(ErrorCode.OPERATION_ERROR, () -> uploadService.resolveCompletedUpload(UPLOAD_ID, user));
        }

        @Test
        void shouldResolveOwnedCompletedFile() throws Exception {
            UploadSession session = completedSession();
            when(uploadSessionMapper.selectOne(any())).thenReturn(session);
            Path file = writeCompletedFile(session, FILE_BYTES);

            CompletedUploadFile resolved = uploadService.resolveCompletedUpload(" " + UPLOAD_ID + " ", user);

            assertEquals(file.toAbsolutePath().normalize(), resolved.path());
            assertEquals("data.csv", resolved.originalFileName());
            assertEquals("csv", resolved.sourceFileType());
            assertEquals(10L, resolved.totalSize());
            assertEquals(session.getWholeFileSha256(), resolved.wholeFileSha256());
        }

        private Path writeCompletedFile(UploadSession session, byte[] content) throws IOException {
            Path file = stagingRoot.resolve(session.getCompletedStorageKey());
            Files.createDirectories(file.getParent());
            return Files.write(file, content);
        }
    }

    // endregion

    // region fixtures

    private UploadSession session(String status) {
        UploadSession session = new UploadSession();
        session.setId(SESSION_ID);
        session.setUploadId(UPLOAD_ID);
        session.setUserId(USER_ID);
        session.setOriginalFileName("data.csv");
        session.setSourceFileType("csv");
        session.setTotalSize((long) FILE_BYTES.length);
        session.setChunkSize(CHUNK_SIZE);
        session.setTotalChunks(3);
        session.setStorageKey(STORAGE_KEY);
        session.setStatus(status);
        session.setExpiresAt(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        return session;
    }

    private UploadSession completedSession() {
        UploadSession session = session("completed");
        String sha = sha256(FILE_BYTES);
        session.setWholeFileSha256(sha);
        session.setCompletedStorageKey(STORAGE_KEY + "/completed-" + sha + ".data");
        session.setCompletedAt(new Date());
        return session;
    }

    private static byte[] chunk(int index) {
        int start = index * CHUNK_SIZE;
        return Arrays.copyOfRange(FILE_BYTES, start, Math.min(start + CHUNK_SIZE, FILE_BYTES.length));
    }

    private static UploadChunk chunkRecord(int index, String sha, int size) {
        UploadChunk chunk = new UploadChunk();
        chunk.setUploadSessionId(SESSION_ID);
        chunk.setChunkIndex(index);
        chunk.setSha256(sha);
        chunk.setChunkSize(size);
        return chunk;
    }

    private static List<UploadChunk> chunkRecords(int... indexes) {
        List<UploadChunk> records = new ArrayList<>();
        for (int index : indexes) {
            records.add(chunkRecord(index, sha256(chunk(index)), chunk(index).length));
        }
        return records;
    }

    private Path sessionDirectory() {
        return stagingRoot.resolve(STORAGE_KEY);
    }

    private Path chunkFile(int index, byte[] content) {
        return sessionDirectory().resolve("chunk-%08d-%s.part".formatted(index, sha256(content)));
    }

    private static long countEntries(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertBusinessError(ErrorCode expected, Executable executable) {
        BusinessException exception = assertThrows(BusinessException.class, executable);
        assertEquals(expected.getCode(), exception.getCode(), exception.getMessage());
    }

    // endregion
}
