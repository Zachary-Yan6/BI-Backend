package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.config.UploadProperties;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.scheduler.UploadCleanupTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Puts upload sessions into the states the cleanup task exists for, then runs the task directly.
 */
class UploadCleanupIT extends AbstractIntegrationTest {

    /** Several CHUNK_SIZE (16-byte) chunks, so uploads exercise real chunking. */
    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\nMar,15\nApr,18\nMay,21\n"
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    private UploadCleanupTask cleanupTask;

    @Autowired
    private UploadProperties uploadProperties;

    @Test
    void abandonedUpload_shouldBeExpiredAndItsChunksDeleted() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);
        assertSuccess(uploadChunk(user, uploadId, 0, CSV));
        // expiresAt is written by the JVM, so it is moved into the past with a JVM timestamp too.
        jdbcTemplate.update("update upload_session set expiresAt = ? where uploadId = ?",
                new Date(System.currentTimeMillis() - Duration.ofHours(1).toMillis()), uploadId);
        Path directory = ageSessionDirectory(uploadId);

        cleanupTask.cleanUp();

        assertThat(sessionStatus(uploadId)).isEqualTo("expired");
        assertThat(directory).doesNotExist();
        assertThat(jdbcTemplate.queryForObject("select count(*) from upload_chunk c join upload_session s "
                + "on c.uploadSessionId = s.id where s.uploadId = ?", Integer.class, uploadId)).isZero();
    }

    @Test
    void completionAbandonedByCrashedProcess_shouldBeReleasedSoUserCanComplete() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);
        for (int index = 0; index * CHUNK_SIZE < CSV.length; index++) {
            assertSuccess(uploadChunk(user, uploadId, index, CSV));
        }
        // A process claimed completion and died before merging.
        jdbcTemplate.update("update upload_session set status = 'completing', completionClaimId = 'dead-process', "
                + "completionStartedAt = NOW() - INTERVAL 1 HOUR where uploadId = ?", uploadId);
        assertErrorCode(complete(user, uploadId), 50001);

        cleanupTask.cleanUp();

        assertThat(sessionStatus(uploadId)).isEqualTo("uploading");
        JsonNode completed = assertSuccess(complete(user, uploadId));
        assertThat(completed.get("fileToken").asText()).isEqualTo(uploadId);
    }

    @Test
    void completedUploadPastRetention_shouldBeDeletedAndRejectedForAnalysis() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        // completedAt is written with MySQL's NOW(), so it is aged with the database clock.
        jdbcTemplate.update("update upload_session set completedAt = NOW() - INTERVAL 8 DAY where uploadId = ?",
                fileToken);
        Path directory = ageSessionDirectory(fileToken);

        cleanupTask.cleanUp();

        assertThat(sessionStatus(fileToken)).isEqualTo("expired");
        assertThat(directory).doesNotExist();
        JsonNode response = postJson("/chart/quality-check", user.session(), Map.of("fileToken", fileToken));
        assertErrorCode(response, 50001);
        assertThat(response.get("message").asText()).contains("expired");
    }

    @Test
    void recentUploadsAndOrphanedDirectories_shouldBeHandledByAge() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        Path orphan = Files.createDirectory(stagingRoot().resolve(UUID.randomUUID().toString()));
        Files.writeString(orphan.resolve("chunk-00000000-leftover.part"), "left behind by a crash");
        age(orphan);

        cleanupTask.cleanUp();

        assertThat(orphan).as("directory without a session row").doesNotExist();
        assertThat(sessionStatus(fileToken)).isEqualTo("completed");
        assertSuccess(postJson("/chart/quality-check", user.session(), Map.of("fileToken", fileToken)));
    }

    // region helpers

    private JsonNode complete(TestUser user, String uploadId) throws Exception {
        return postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of());
    }

    private String sessionStatus(String uploadId) {
        return jdbcTemplate.queryForObject("select status from upload_session where uploadId = ?", String.class,
                uploadId);
    }

    private Path stagingRoot() {
        return Path.of(uploadProperties.getStagingDirectory()).toAbsolutePath().normalize();
    }

    /** The cleanup never touches directories modified within the last hour, so pretend this one is older. */
    private Path ageSessionDirectory(String uploadId) throws IOException {
        String storageKey = jdbcTemplate.queryForObject("select storageKey from upload_session where uploadId = ?",
                String.class, uploadId);
        Path directory = stagingRoot().resolve(storageKey);
        age(directory);
        return directory;
    }

    private static void age(Path directory) throws IOException {
        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
    }

    // endregion
}
