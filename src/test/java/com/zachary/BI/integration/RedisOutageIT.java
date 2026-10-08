package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.common.ErrorCode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import com.zachary.BI.integration.support.IntegrationTestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Redis only backs the rate limiter, so losing it must not stop analysis or mark the backend unhealthy.
 * Pausing the container makes Redis stop answering without closing connections, like a hung server or a broken
 * network, which is the slower and harder failure to handle.
 */
class RedisOutageIT extends AbstractIntegrationTest {

    private static final byte[] CSV = "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8);
    private static final String AI_RESPONSE = "{\"series\":[{\"type\":\"line\",\"data\":[10,12]}]}\n-----\nSales grow.";

    @Test
    void whileRedisIsUnreachable_analysisAndHealthShouldKeepWorking() throws Exception {
        when(genAi.doChat(anyString())).thenReturn(AI_RESPONSE);
        TestUser user = registerAndLogin();
        String fileToken = uploadFile(user, "sales.csv", CSV);
        var docker = IntegrationTestContainers.REDIS.getDockerClient();
        String redis = IntegrationTestContainers.REDIS.getContainerId();

        docker.pauseContainerCmd(redis).exec();
        try {
            // Previously: "System error" after waiting the full 5 s Redis timeout.
            long start = System.nanoTime();
            long jobId = assertSuccess(generate(user, fileToken, "During outage 1")).get("jobId").asLong();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertSuccess(generate(user, fileToken, "During outage 2"));

            // The 2-per-second window still applies, enforced by this instance.
            assertErrorCode(generate(user, fileToken, "During outage 3"), ErrorCode.TOO_MANY_REQUESTS.getCode());

            // Previously 503, which made deploy.sh roll back a healthy release.
            JsonNode health = objectMapper.readTree(mockMvc.perform(get("/actuator/health"))
                    .andReturn().getResponse().getContentAsString());
            assertThat(health.get("status").asText()).isEqualTo("UP");

            // Sessions never depended on Redis.
            assertSuccess(postJson("/user/login", new MockHttpSession(),
                    Map.of("userAccount", user.account(), "userPassword", PASSWORD)));

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                    "select status from analysis_job where id = ?", String.class, jobId)).isEqualTo("succeeded"));
        } finally {
            docker.unpauseContainerCmd(redis).exec();
        }

        // Generation keeps working after Redis returns; the limiter tries Redis again a few seconds after a failure.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertSuccess(generate(user, fileToken, "After outage")));
    }

    private JsonNode generate(TestUser user, String fileToken, String name) throws Exception {
        return postJson("/chart/gen", user.session(), Map.of("fileToken", fileToken, "name", name,
                "goal", "Show the trend", "chartType", "line", "qualityAcknowledged", false));
    }
}
