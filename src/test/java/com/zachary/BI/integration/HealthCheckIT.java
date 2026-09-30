package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The deployment pipeline trusts this endpoint to decide whether a release is healthy.
 */
class HealthCheckIT extends AbstractIntegrationTest {

    @Test
    void health_shouldBeUpWhenAllInfrastructureIsReachable() throws Exception {
        String body = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode health = objectMapper.readTree(body);
        assertThat(health.get("status").asText()).isEqualTo("UP");
        // Component details must not be exposed publicly.
        assertThat(health.has("components")).isFalse();
    }

    @Test
    void otherActuatorEndpoints_shouldNotBeExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
