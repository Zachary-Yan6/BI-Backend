package com.zachary.BI.service.impl;

import com.zachary.BI.config.UploadProperties;
import com.zachary.BI.mapper.UploadChunkMapper;
import com.zachary.BI.mapper.UploadSessionMapper;
import com.zachary.BI.model.entity.UploadChunk;
import com.zachary.BI.model.entity.UploadSession;
import com.zachary.BI.support.MybatisPlusTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UploadCleanupServiceImplTest {

    @Mock
    private UploadSessionMapper uploadSessionMapper;

    @Mock
    private UploadChunkMapper uploadChunkMapper;

    @InjectMocks
    private UploadCleanupServiceImpl cleanupService;

    @TempDir
    Path stagingRoot;

    private UploadProperties uploadProperties;

    @BeforeAll
    static void initLambdaCache() {
        MybatisPlusTestSupport.initTableInfo(UploadSession.class, UploadChunk.class);
    }

    @BeforeEach
    void setUp() {
        uploadProperties = new UploadProperties();
        uploadProperties.setStagingDirectory(stagingRoot.toString());
        ReflectionTestUtils.setField(cleanupService, "uploadProperties", uploadProperties);
    }

    @Test
    void statusTransitions_shouldPassConfiguredDurationsToMapper() {
        uploadProperties.setCompletionLeaseMinutes(10);
        uploadProperties.setCompletedRetentionHours(168);
        when(uploadSessionMapper.releaseStaleCompletions(600)).thenReturn(1);
        when(uploadSessionMapper.expireCompletedUploads(604_800)).thenReturn(2);
        when(uploadSessionMapper.expireAbandonedSessions(any(Date.class))).thenReturn(3);

        assertEquals(1, cleanupService.releaseStaleCompletions());
        assertEquals(2, cleanupService.expireCompletedUploads());
        assertEquals(3, cleanupService.expireAbandonedSessions());
    }

    @Test
    void deleteUnusedStorage_shouldDeleteOnlyOldDirectoriesTheDatabaseNoLongerNeeds() throws Exception {
        Path orphan = sessionDirectory(true);
        Path expired = sessionDirectory(true);
        Path aborted = sessionDirectory(true);
        Path uploading = sessionDirectory(true);
        Path completed = sessionDirectory(true);
        Path youngOrphan = sessionDirectory(false);
        Path foreign = Files.createDirectory(stagingRoot.resolve("not-a-storage-key"));
        age(foreign);
        when(uploadSessionMapper.selectList(any())).thenReturn(List.of(
                session(11L, expired, "expired"),
                session(12L, aborted, "aborted"),
                session(13L, uploading, "uploading"),
                session(14L, completed, "completed")));

        assertEquals(3, cleanupService.deleteUnusedStorage());

        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(expired));
        assertFalse(Files.exists(aborted));
        assertTrue(Files.exists(uploading.resolve("chunk.part")), "active upload kept");
        assertTrue(Files.exists(completed.resolve("chunk.part")), "completed upload within retention kept");
        assertTrue(Files.exists(youngOrphan), "may belong to a session being created right now");
        assertTrue(Files.exists(foreign), "only server-generated storage keys are ever deleted");
        // Chunk rows are removed for the two sessions whose files were deleted.
        verify(uploadChunkMapper, times(2)).delete(any());
    }

    @Test
    void deleteUnusedStorage_whenStagingRootMissing_shouldDoNothing() {
        uploadProperties.setStagingDirectory(stagingRoot.resolve("missing").toString());

        assertEquals(0, cleanupService.deleteUnusedStorage());
        verifyNoInteractions(uploadSessionMapper, uploadChunkMapper);
    }

    @Test
    void deleteUnusedStorage_withNoCandidates_shouldSkipQueries() throws Exception {
        sessionDirectory(false);

        assertEquals(0, cleanupService.deleteUnusedStorage());
        verifyNoInteractions(uploadSessionMapper, uploadChunkMapper);
    }

    private Path sessionDirectory(boolean old) throws IOException {
        Path directory = Files.createDirectory(stagingRoot.resolve(UUID.randomUUID().toString()));
        Files.writeString(directory.resolve("chunk.part"), "data");
        if (old) {
            age(directory);
        }
        return directory;
    }

    private static void age(Path directory) throws IOException {
        Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        Files.setLastModifiedTime(directory, FileTime.from(twoHoursAgo));
    }

    private static UploadSession session(long id, Path directory, String status) {
        UploadSession session = new UploadSession();
        session.setId(id);
        session.setStorageKey(directory.getFileName().toString());
        session.setStatus(status);
        return session;
    }
}
