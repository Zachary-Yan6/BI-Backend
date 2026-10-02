package com.zachary.BI.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.zachary.BI.integration.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ChartManagementIT extends AbstractIntegrationTest {

    @Test
    void ownerCanCreateEditAndSoftDeleteChart() throws Exception {
        TestUser owner = registerAndLogin();

        long chartId = assertSuccess(postJson("/chart/add", owner.session(), Map.of(
                "name", "Revenue", "goal", "Show growth", "chartData", "month,sales\nJan,10\n", "chartType", "line")))
                .asLong();
        assertThat(jdbcTemplate.queryForObject("select userId from chart where id = ?", Long.class, chartId))
                .isEqualTo(owner.id());

        assertSuccess(postJson("/chart/edit", owner.session(), Map.of("id", chartId, "name", "Revenue v2")));
        JsonNode chart = assertSuccess(perform(get("/chart/get").param("id", String.valueOf(chartId))));
        assertThat(chart.get("name").asText()).isEqualTo("Revenue v2");
        assertThat(chart.get("goal").asText()).isEqualTo("Show growth");

        assertSuccess(postJson("/chart/delete", owner.session(), Map.of("id", chartId)));
        assertThat(jdbcTemplate.queryForObject("select isDelete from chart where id = ?", Integer.class, chartId))
                .isEqualTo(1);
        assertErrorCode(perform(get("/chart/get").param("id", String.valueOf(chartId))), 40400);
    }

    @Test
    void demotedAdmin_shouldLoseAdminRightsWithoutLoggingOut() throws Exception {
        TestUser owner = registerAndLogin();
        TestUser formerAdmin = registerAndLoginAdmin();
        long chartId = insertChart(owner.id(), Map.of("name", "Owned by someone else"));
        // The session still holds the User object captured at login, when the role was admin.
        jdbcTemplate.update("update user set userRole = 'user' where id = ?", formerAdmin.id());

        assertErrorCode(postJson("/chart/delete", formerAdmin.session(), Map.of("id", chartId)), 40101);
        assertThat(jdbcTemplate.queryForObject("select isDelete from chart where id = ?", Integer.class, chartId))
                .isZero();
    }

    @Test
    void otherUsersCannotEditOrDeleteChart_butAdminCan() throws Exception {
        TestUser owner = registerAndLogin();
        TestUser stranger = registerAndLogin();
        TestUser admin = registerAndLoginAdmin();
        long chartId = insertChart(owner.id(), Map.of("name", "Private"));

        assertErrorCode(postJson("/chart/edit", stranger.session(), Map.of("id", chartId, "name", "Hacked")), 40101);
        assertErrorCode(postJson("/chart/delete", stranger.session(), Map.of("id", chartId)), 40101);
        assertThat(jdbcTemplate.queryForObject("select name from chart where id = ?", String.class, chartId))
                .isEqualTo("Private");

        assertSuccess(postJson("/chart/delete", admin.session(), Map.of("id", chartId)));
        assertThat(jdbcTemplate.queryForObject("select isDelete from chart where id = ?", Integer.class, chartId))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"createTime", ""})
    void paging_throughChartsWithEqualSortValues_shouldReturnEachChartExactlyOnce(String sortField) throws Exception {
        TestUser owner = registerAndLogin();
        Set<Long> inserted = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            // Same second for every row: createTime alone cannot order them.
            inserted.add(insertChart(owner.id(), Map.of("name", "Tie " + i, "createTime", "2026-01-01 00:00:00")));
        }

        List<Long> seen = new ArrayList<>();
        for (int current = 1; current <= 3; current++) {
            JsonNode page = assertSuccess(postJson("/chart/my/list/page/vo", owner.session(),
                    Map.of("current", current, "pageSize", 10, "sortField", sortField, "sortOrder", "descend")));
            page.get("records").forEach(chart -> seen.add(chart.get("id").asLong()));
        }

        // With ties and LIMIT, MySQL may return tied rows in a different order for each page, so without a unique
        // tie-breaker some charts show up on two pages and others on none.
        assertThat(seen).hasSize(30).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(inserted);
    }

    @Test
    void sortingByAColumnOutsideTheAllowlist_shouldBeAParameterError() throws Exception {
        TestUser owner = registerAndLogin();

        // Previously any identifier-shaped name reached SQL: an unknown column became a 500.
        assertErrorCode(postJson("/chart/my/list/page/vo", owner.session(),
                Map.of("sortField", "noSuchColumn")), 40000);
        assertErrorCode(postJson("/chart/my/list/page/vo", owner.session(),
                Map.of("sortField", "chartData")), 40000);
    }

    @Test
    void myChartList_shouldOnlyReturnCurrentUsersChartsWithRealPagination() throws Exception {
        TestUser owner = registerAndLogin();
        TestUser other = registerAndLogin();
        for (int i = 0; i < 3; i++) {
            insertChart(owner.id(), Map.of("name", "Mine " + i));
        }
        insertChart(other.id(), Map.of("name", "Not mine"));

        // Even a spoofed userId is overridden with the session user.
        JsonNode page = assertSuccess(postJson("/chart/my/list/page/vo", owner.session(),
                Map.of("current", 1, "pageSize", 2, "userId", other.id())));

        // total comes from the MyBatis-Plus pagination interceptor's COUNT query.
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("records")).hasSize(2);
        assertThat(names(page)).allMatch(name -> name.startsWith("Mine"));
    }

    @Test
    void myChartList_shouldApplyAdvancedFiltersInSql() throws Exception {
        TestUser owner = registerAndLogin();
        LocalDateTime march = LocalDateTime.of(2026, 3, 15, 23, 30);
        insertChart(owner.id(), Map.of("name", "Legacy queued", "status", "wait", "chartType", "bar"));
        insertChart(owner.id(), Map.of("name", "Legacy done", "status", "succeed", "chartType", "line",
                "generatedAt", Timestamp.valueOf(march), "sourceFileSize", 500L, "sourceFileType", "xlsx"));
        insertChart(owner.id(), Map.of("name", "Running", "status", "running", "chartType", "pie",
                "sourceFileSize", 5_000L, "sourceFileType", "csv"));
        insertChart(owner.id(), Map.of("name", "Keyword in file", "sourceFileName", "quarterly-invoices.csv"));
        insertChart(owner.id(), Map.of("name", "Keyword in data", "chartData", "product,invoices\nA,1\n"));

        // Legacy status values are matched by their current names.
        assertThat(myChartNames(owner, Map.of("statuses", List.of("queued")))).containsExactly("Legacy queued");
        assertThat(myChartNames(owner, Map.of("statuses", List.of("completed")))).containsExactly("Legacy done");
        assertThat(myChartNames(owner, Map.of("statuses", List.of("analyzing")))).containsExactly("Running");

        assertThat(myChartNames(owner, Map.of("chartTypes", List.of("bar", "pie"))))
                .containsExactlyInAnyOrder("Legacy queued", "Running");
        assertThat(myChartNames(owner, Map.of("minSourceFileSize", 100, "maxSourceFileSize", 1_000)))
                .containsExactly("Legacy done");
        assertThat(myChartNames(owner, Map.of("sourceFileType", "csv"))).containsExactly("Running");

        // The end date is inclusive even for a late-evening generation time.
        assertThat(myChartNames(owner, Map.of("generatedFrom", "2026-03-15", "generatedTo", "2026-03-15")))
                .containsExactly("Legacy done");
        assertThat(myChartNames(owner, Map.of("generatedFrom", "2026-03-16"))).isEmpty();

        // One keyword searches the title, original file name and stored CSV data.
        assertThat(myChartNames(owner, Map.of("keyWord", "invoices")))
                .containsExactlyInAnyOrder("Keyword in file", "Keyword in data");
    }

    @Test
    void chartListing_shouldValidateLimitsAndAdminRole() throws Exception {
        TestUser user = registerAndLogin();

        assertErrorCode(postJson("/chart/my/list/page/vo", user.session(), Map.of("pageSize", 21)), 40000);
        assertErrorCode(postJson("/chart/my/list/page/vo", user.session(),
                Map.of("minSourceFileSize", 10, "maxSourceFileSize", 1)), 40000);
        assertErrorCode(postJson("/chart/list/page", user.session(), Map.of()), 40101);

        TestUser admin = registerAndLoginAdmin();
        long chartId = insertChart(user.id(), Map.of("name", "Visible to admin"));
        JsonNode page = assertSuccess(postJson("/chart/list/page", admin.session(),
                Map.of("id", chartId, "pageSize", 50)));
        assertThat(names(page)).containsExactly("Visible to admin");
    }

    private List<String> myChartNames(TestUser user, Map<String, Object> filters) throws Exception {
        Map<String, Object> request = new HashMap<>(filters);
        request.put("pageSize", 20);
        return names(assertSuccess(postJson("/chart/my/list/page/vo", user.session(), request)));
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("records").forEach(record -> names.add(record.get("name").asText()));
        return names;
    }

    private long insertChart(long userId, Map<String, Object> columns) {
        Map<String, Object> values = new HashMap<>(Map.of("userId", userId, "goal", "goal", "status", "failed"));
        values.putAll(columns);
        List<String> names = new ArrayList<>(values.keySet());
        String sql = "insert into chart (" + String.join(", ", names) + ") values ("
                + String.join(", ", names.stream().map(name -> "?").toList()) + ")";
        jdbcTemplate.update(sql, names.stream().map(values::get).toArray());
        return jdbcTemplate.queryForObject("select max(id) from chart where userId = ?", Long.class, userId);
    }
}
