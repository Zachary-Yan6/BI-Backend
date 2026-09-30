package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class UserAccountIT extends AbstractIntegrationTest {

    private static final int PARAMS_ERROR = 40000;
    private static final int NOT_LOGIN_ERROR = 40100;
    private static final int NO_AUTH_ERROR = 40101;

    @Test
    void registerLoginUpdateAndLogout_shouldRoundTripThroughSessionAndDatabase() throws Exception {
        TestUser user = registerAndLogin();

        JsonNode me = assertSuccess(perform(get("/user/get/login").session(user.session())));
        // JsonConfig serialises Long values as strings so JavaScript clients keep full snowflake precision.
        assertThat(me.get("id").isTextual()).isTrue();
        assertThat(me.get("id").asLong()).isEqualTo(user.id());
        assertThat(me.get("userRole").asText()).isEqualTo("user");

        assertSuccess(postJson("/user/update/my", user.session(), Map.of("userName", "Integration User")));
        assertThat(jdbcTemplate.queryForObject("select userName from user where id = ?", String.class, user.id()))
                .isEqualTo("Integration User");

        assertSuccess(postJson("/user/logout", user.session(), Map.of()));
        assertErrorCode(perform(get("/user/get/login").session(user.session())), NOT_LOGIN_ERROR);
    }

    @Test
    void register_shouldStoreOnlyHashedPassword() throws Exception {
        TestUser user = registerAndLogin();

        String stored = jdbcTemplate.queryForObject("select userPassword from user where id = ?", String.class, user.id());

        assertThat(stored).isNotEqualTo(PASSWORD).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void register_withExistingAccount_shouldBeRejected() throws Exception {
        TestUser user = registerAndLogin();

        JsonNode response = postJson("/user/register", null, Map.of(
                "userAccount", user.account(),
                "userPassword", PASSWORD,
                "checkPassword", PASSWORD));

        assertErrorCode(response, PARAMS_ERROR);
        assertThat(jdbcTemplate.queryForObject("select count(*) from user where userAccount = ?",
                Integer.class, user.account())).isEqualTo(1);
    }

    @Test
    void login_withWrongPassword_shouldNotCreateSession() throws Exception {
        TestUser user = registerAndLogin();
        MockHttpSession session = new MockHttpSession();

        assertErrorCode(postJson("/user/login", session,
                Map.of("userAccount", user.account(), "userPassword", "wrong-password")), PARAMS_ERROR);
        assertErrorCode(perform(get("/user/get/login").session(session)), NOT_LOGIN_ERROR);
    }

    @Test
    void adminEndpoints_shouldBeGuardedByAuthCheckAspect() throws Exception {
        Map<String, Object> pageRequest = Map.of("current", 1, "pageSize", 10);

        assertErrorCode(postJson("/user/list/page", new MockHttpSession(), pageRequest), NOT_LOGIN_ERROR);

        TestUser regular = registerAndLogin();
        assertErrorCode(postJson("/user/list/page", regular.session(), pageRequest), NO_AUTH_ERROR);

        TestUser admin = registerAndLoginAdmin();
        JsonNode page = assertSuccess(postJson("/user/list/page", admin.session(), pageRequest));
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(page.get("records")).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void bannedUser_shouldBeRejectedEvenWithAdminOnlyRoleCheck() throws Exception {
        TestUser user = registerAndLogin();
        jdbcTemplate.update("update user set userRole = 'ban' where id = ?", user.id());

        // getLoginUser reloads the user from MySQL, so the new role applies to the existing session.
        assertErrorCode(postJson("/user/list/page", user.session(), Map.of()), NO_AUTH_ERROR);
    }

    @Test
    void admin_shouldManageOtherUsers() throws Exception {
        TestUser admin = registerAndLoginAdmin();

        long createdId = assertSuccess(postJson("/user/add", admin.session(),
                Map.of("userAccount", "created" + admin.id(), "userName", "Created"))).asLong();
        assertSuccess(postJson("/user/update", admin.session(), Map.of("id", createdId, "userName", "Renamed")));

        JsonNode fetched = assertSuccess(perform(get("/user/get").param("id", String.valueOf(createdId))
                .session(admin.session())));
        assertThat(fetched.get("userName").asText()).isEqualTo("Renamed");

        assertSuccess(postJson("/user/delete", admin.session(), Map.of("id", createdId)));
        // Deletion is logical: the row stays, but MyBatis-Plus hides it from reads.
        assertThat(jdbcTemplate.queryForObject("select isDelete from user where id = ?", Integer.class, createdId))
                .isEqualTo(1);
        assertErrorCode(perform(get("/user/get").param("id", String.valueOf(createdId)).session(admin.session())),
                40400);
    }
}
