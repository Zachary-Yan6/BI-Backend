package com.zachary.BI.integration.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.BI.GenAi;
import com.zachary.BI.service.AnalysisJobService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole application against real MySQL, Redis and RabbitMQ.
 * Only the external AI provider is replaced, so tests never spend API credit or depend on its availability.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
// @ExtendWith is inherited by subclasses (unlike @EnabledIf), so every *IT class gets the Docker check.
@ExtendWith(RequiresDockerCondition.class)
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "password123";

    /** Small enough that a few lines of CSV span several chunks. */
    protected static final int CHUNK_SIZE = 16;

    /** Created once: a property supplier is re-evaluated on every lookup, so it must return a stable value. */
    private static final String STAGING_DIRECTORY = createStagingDirectory();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @MockitoBean
    protected GenAi genAi;

    /**
     * Calls the real service unless a test stubs it, and is reset after every test.
     * Declared here rather than in the one test class that injects failures: a bean override in a single class
     * gives it its own Spring context, and because cached contexts stay alive, that context's RabbitMQ consumers
     * would keep competing for the shared queue (with an unstubbed GenAi mock) and break later test classes.
     */
    @MockitoSpyBean
    protected AnalysisJobService analysisJobService;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.start();

        registry.add("spring.datasource.url", IntegrationTestContainers.MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestContainers.MYSQL::getUsername);
        registry.add("spring.datasource.password", IntegrationTestContainers.MYSQL::getPassword);

        registry.add("spring.data.redis.host", IntegrationTestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> IntegrationTestContainers.REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", IntegrationTestContainers.RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", IntegrationTestContainers.RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", IntegrationTestContainers.RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", IntegrationTestContainers.RABBITMQ::getAdminPassword);

        registry.add("bi.upload.staging-directory", () -> STAGING_DIRECTORY);
        registry.add("bi.upload.chunk-size-bytes", () -> CHUNK_SIZE);
        // Never reach the real provider, even if a developer's .env contains a key.
        registry.add("bi.ai.api-key", () -> "");
    }

    private static String createStagingDirectory() {
        try {
            return Files.createTempDirectory("bi-it-uploads").toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // region HTTP helpers

    /** Registers a fresh account and returns an authenticated session for it. */
    protected TestUser registerAndLogin() throws Exception {
        return registerAndLogin("user");
    }

    protected TestUser registerAndLoginAdmin() throws Exception {
        return registerAndLogin("admin");
    }

    private TestUser registerAndLogin(String role) throws Exception {
        String account = "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode registered = postJson("/user/register", null, Map.of(
                "userAccount", account,
                "userPassword", PASSWORD,
                "checkPassword", PASSWORD));
        long userId = assertSuccess(registered).asLong();
        // Set the role before login: some checks read the User snapshot stored in the session at login time.
        jdbcTemplate.update("update user set userRole = ? where id = ?", role, userId);

        MockHttpSession session = new MockHttpSession();
        assertSuccess(postJson("/user/login", session, Map.of("userAccount", account, "userPassword", PASSWORD)));
        return new TestUser(userId, account, session);
    }

    protected JsonNode postJson(String path, MockHttpSession session, Object body) throws Exception {
        var request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (session != null) {
            request.session(session);
        }
        return perform(request);
    }

    protected JsonNode perform(RequestBuilder request) throws Exception {
        String body = mockMvc.perform(request)
                // Business errors are reported in the body; the HTTP status is always 200.
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    protected static JsonNode assertSuccess(JsonNode response) {
        assertThat(response.get("code").asInt()).as("response %s", response).isZero();
        return response.get("data");
    }

    protected static void assertErrorCode(JsonNode response, int expectedCode) {
        assertThat(response.get("code").asInt()).as("response %s", response).isEqualTo(expectedCode);
    }

    // endregion

    // region resumable upload helpers

    /** Uploads {@code content} through the real chunked-upload API and returns the completed file token. */
    protected String uploadFile(TestUser user, String fileName, byte[] content) throws Exception {
        String uploadId = createUploadSession(user, fileName, content.length);
        for (int index = 0; index * CHUNK_SIZE < content.length; index++) {
            assertSuccess(uploadChunk(user, uploadId, index, content));
        }
        return assertSuccess(postJson("/upload/sessions/" + uploadId + "/complete", user.session(), Map.of()))
                .get("fileToken").asText();
    }

    protected String createUploadSession(TestUser user, String fileName, long totalSize) throws Exception {
        JsonNode session = assertSuccess(postJson("/upload/sessions", user.session(), Map.of(
                "fileName", fileName,
                "totalSize", totalSize,
                "contentType", "text/csv")));
        return session.get("uploadId").asText();
    }

    protected JsonNode uploadChunk(TestUser user, String uploadId, int index, byte[] content) throws Exception {
        int start = index * CHUNK_SIZE;
        byte[] chunk = Arrays.copyOfRange(content, start, Math.min(start + CHUNK_SIZE, content.length));
        return perform(put("/upload/sessions/{uploadId}/chunks/{index}", uploadId, index)
                .session(user.session())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Range", "bytes %d-%d/%d".formatted(start, start + chunk.length - 1, content.length))
                .header("X-Chunk-SHA256", sha256(chunk))
                .content(chunk));
    }

    protected static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // endregion

    protected record TestUser(long id, String account, MockHttpSession session) {
    }
}
