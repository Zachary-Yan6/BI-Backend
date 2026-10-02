package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.model.entity.User;
import com.zachary.BI.service.ResumableUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ResumableUploadIT extends AbstractIntegrationTest {

    /** 53 bytes: four chunks of 16, 16, 16 and 5 bytes. */
    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\nMar,15\nApr,18\nMay,21\nJun,2\n"
            .getBytes(StandardCharsets.UTF_8);

    @Value("${bi.upload.staging-directory}")
    private String stagingDirectory;

    @Autowired
    private ResumableUploadService resumableUploadService;

    @Test
    void chunkedUpload_outOfOrderAndRetried_shouldMergeIntoVerifiedFile() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);

        // Chunks may arrive in any order, and an identical retry is accepted without a second write.
        for (int index : new int[]{3, 1, 0, 2}) {
            assertThat(assertSuccess(uploadChunk(user, uploadId, index, CSV)).get("alreadyUploaded").asBoolean())
                    .isFalse();
        }
        assertThat(assertSuccess(uploadChunk(user, uploadId, 1, CSV)).get("alreadyUploaded").asBoolean()).isTrue();

        JsonNode status = assertSuccess(perform(get("/upload/sessions/" + uploadId).session(user.session())));
        assertThat(status.get("status").asText()).isEqualTo("uploading");
        assertThat(status.get("uploadedChunkIndexes").toString()).isEqualTo("[0,1,2,3]");
        assertThat(status.get("readyToComplete").asBoolean()).isTrue();

        JsonNode completed = assertSuccess(postJson("/upload/sessions/" + uploadId + "/complete",
                user.session(), Map.of()));
        assertThat(completed.get("fileToken").asText()).isEqualTo(uploadId);
        assertThat(completed.get("wholeFileSha256").asText()).isEqualTo(sha256(CSV));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, completedStorageKey, completionClaimId from upload_session where uploadId = ?", uploadId);
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("completionClaimId")).isNull();
        Path merged = Path.of(stagingDirectory).resolve((String) row.get("completedStorageKey"));
        assertThat(Files.readAllBytes(merged)).isEqualTo(CSV);
        // Only the merged file remains once the chunk parts are cleaned up.
        try (var files = Files.list(merged.getParent())) {
            assertThat(files).containsExactly(merged);
        }

        // Completing again is idempotent.
        assertThat(assertSuccess(postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of()))
                .get("wholeFileSha256").asText()).isEqualTo(sha256(CSV));
    }

    @Test
    void completedUpload_shouldBeInspectableByToken() throws Exception {
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);

        JsonNode report = assertSuccess(postJson("/chart/quality-check", user.session(), Map.of("fileToken", fileToken)));

        assertThat(report.get("totalRows").asInt()).isEqualTo(6);
        assertThat(report.get("columnCount").asInt()).isEqualTo(2);
        assertThat(report.get("recommendation").asText()).isEqualTo("READY");
    }

    @Test
    void uploadSessions_shouldBeInvisibleToOtherUsers() throws Exception {
        TestUser owner = registerAndLogin();
        TestUser stranger = registerAndLogin();
        String fileToken = uploadFile(owner, "sales.csv", CSV);

        assertErrorCode(perform(get("/upload/sessions/" + fileToken).session(stranger.session())), 40400);
        assertErrorCode(uploadChunk(stranger, fileToken, 0, CSV), 40400);
        assertErrorCode(postJson("/upload/sessions/" + fileToken + "/complete", stranger.session(), Map.of()), 40400);
        assertErrorCode(postJson("/chart/quality-check", stranger.session(), Map.of("fileToken", fileToken)), 40400);
    }

    @Test
    void completingWithMissingChunks_shouldReleaseClaimSoUserCanRetry() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);
        assertSuccess(uploadChunk(user, uploadId, 0, CSV));

        assertErrorCode(postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of()), 40000);

        // releaseCompletion must have returned the session to an uploadable state.
        assertThat(jdbcTemplate.queryForMap(
                "select status, completionClaimId from upload_session where uploadId = ?", uploadId))
                .containsEntry("status", "uploading")
                .containsEntry("completionClaimId", null);

        for (int index = 1; index < 4; index++) {
            assertSuccess(uploadChunk(user, uploadId, index, CSV));
        }
        assertSuccess(postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of()));
    }

    @Test
    void expiredSession_shouldRejectChunksAndCompletion() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);
        for (int index = 0; index < 4; index++) {
            assertSuccess(uploadChunk(user, uploadId, index, CSV));
        }
        jdbcTemplate.update("update upload_session set expiresAt = NOW() - INTERVAL 1 MINUTE where uploadId = ?",
                uploadId);

        // The claimCompletion SQL checks expiresAt > NOW(), so the claim fails inside MySQL.
        assertErrorCode(postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of()), 50001);

        JsonNode status = assertSuccess(perform(get("/upload/sessions/" + uploadId).session(user.session())));
        assertThat(status.get("status").asText()).isEqualTo("expired");
        assertThat(status.get("readyToComplete").asBoolean()).isFalse();
        assertErrorCode(uploadChunk(user, uploadId, 0, CSV), 50001);
    }

    @Test
    void createSession_shouldRejectUnsupportedTypesAndOversizedFiles() throws Exception {
        TestUser user = registerAndLogin();

        assertErrorCode(postJson("/upload/sessions", user.session(),
                Map.of("fileName", "notes.pdf", "totalSize", 10)), 40000);
        assertErrorCode(postJson("/upload/sessions", user.session(),
                Map.of("fileName", "big.csv", "totalSize", 26_214_401L)), 40000);
    }

    @Test
    void slowFirstChunk_shouldNotOverwriteStatusChangedByAConcurrentRequest() throws Exception {
        TestUser user = registerAndLogin();
        String uploadId = createUploadSession(user, "sales.csv", CSV.length);
        byte[] firstChunk = Arrays.copyOfRange(CSV, 0, CHUNK_SIZE);

        // The first chunk's request read the session while it was still "created". While its body is being
        // received, the other chunks arrive and a completion finishes; simulate that interleaving deterministically
        // by changing the row the moment the body has been fully read.
        InputStream bodyThatLetsOthersFinish = new ByteArrayInputStream(firstChunk) {
            private boolean completedElsewhere;

            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                int read = super.read(buffer, offset, length);
                if (read == -1 && !completedElsewhere) {
                    completedElsewhere = true;
                    jdbcTemplate.update("update upload_session set status = 'completed' where uploadId = ?", uploadId);
                }
                return read;
            }
        };
        User loginUser = new User();
        loginUser.setId(user.id());

        resumableUploadService.uploadChunk(uploadId, 0, "bytes 0-15/" + CSV.length, sha256(firstChunk),
                CHUNK_SIZE, bodyThatLetsOthersFinish, loginUser);

        // A blind full-row update would have written the stale snapshot back as "uploading".
        assertThat(jdbcTemplate.queryForObject("select status from upload_session where uploadId = ?", String.class,
                uploadId)).isEqualTo("completed");
    }
}
