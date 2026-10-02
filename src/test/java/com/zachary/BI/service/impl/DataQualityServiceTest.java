package com.zachary.BI.service.impl;

import com.zachary.BI.model.vo.DataQualityColumn;
import com.zachary.BI.model.vo.DataQualityIssue;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.utils.ExcelUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityServiceTest {

    private final DataQualityService dataQualityService = new DataQualityService();

    @TempDir
    Path tempDir;

    // region real spreadsheet parsing

    @Test
    void inspectMultipart_withCleanCsv_shouldBeReady() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "scores.csv", "text/csv",
                "city,score\nLondon,1\nParis,2\nRome,6\n".getBytes(StandardCharsets.UTF_8));

        DataQualityReport report = dataQualityService.inspect(file);

        assertEquals("READY", report.getRecommendation());
        assertFalse(report.isConfirmationRequired());
        assertEquals(3, report.getTotalRows());
        assertEquals(2, report.getColumnCount());
        assertTrue(report.getIssues().isEmpty());

        assertEquals("TEXT", column(report, "city").getInferredType());
        DataQualityColumn score = column(report, "score");
        assertEquals("NUMBER", score.getInferredType());
        assertEquals(1.0, score.getMinimum());
        assertEquals(6.0, score.getMaximum());
        assertEquals(3.0, score.getAverage());
    }

    @Test
    void inspectPath_shouldReadCompletedUploadFromDisk() throws Exception {
        Path csv = tempDir.resolve("upload.data");
        Files.writeString(csv, "city,score\nLondon,1\nLondon,1\n");

        DataQualityReport report = dataQualityService.inspect(csv, "csv");

        assertEquals(2, report.getTotalRows());
        assertEquals(1, report.getDuplicateRows());
        assertEquals("REVIEW_REQUIRED", report.getRecommendation());
        assertTrue(report.isConfirmationRequired());
        assertHasIssue(report, "WARNING", null, "1 duplicate data row was found.");
    }

    // endregion

    // region structural problems

    @Test
    void inspect_withNoRows_shouldReportMissingHeader() throws Exception {
        DataQualityReport report = inspectRows(List.of());

        assertEquals(0, report.getTotalRows());
        assertEquals(0, report.getColumnCount());
        assertEquals(0, report.getDuplicateRows());
        assertTrue(report.getColumns().isEmpty());
        assertEquals("NOT_RECOMMENDED", report.getRecommendation());
        assertTrue(report.isConfirmationRequired());
        assertHasIssue(report, "ERROR", null, "The file does not contain a header row.");
    }

    @Test
    void inspect_withEmptyHeaderRow_shouldReportNoColumnsAndNoData() throws Exception {
        DataQualityReport report = inspectRows(List.of(List.of()));

        assertHasIssue(report, "ERROR", null, "The header row does not contain any columns.");
        assertHasIssue(report, "ERROR", null, "The file has no data rows to analyse.");
        assertEquals("NOT_RECOMMENDED", report.getRecommendation());
    }

    @Test
    void inspect_withHeaderOnly_shouldMarkColumnsEmpty() throws Exception {
        DataQualityReport report = inspectRows(List.of(List.of("value")));

        DataQualityColumn value = column(report, "value");
        assertEquals("EMPTY", value.getInferredType());
        assertEquals(0.0, value.getNullRate());
        assertHasIssue(report, "ERROR", "value", "This column does not contain any values.");
    }

    @Test
    void inspect_shouldNameBlankHeadersAndFlagDuplicates() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("Region", " ", "region"),
                List.of("North", "a", "x"),
                List.of("South", "b", "y"),
                List.of("South", "b", "y"),
                List.of("South", "b", "y")
        ));

        assertEquals("Column 2", report.getColumns().get(1).getName());
        assertHasIssue(report, "WARNING", "Column 2", "This column has no header name.");
        assertHasIssue(report, "WARNING", "region", "Duplicate column name detected.");
        assertEquals(2, report.getDuplicateRows());
        assertHasIssue(report, "WARNING", null, "2 duplicate data rows were found.");
    }

    // endregion

    // region column inference

    @Test
    void inspect_shouldDetectDateColumnsAndFormatAnomalies() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("period"),
                List.of("2024-01-01"),
                List.of("2024/01/02"),
                List.of("1/2/2024"),
                List.of("2024-01-03 10:00:00"),
                List.of("2024/01/04 11:30:00"),
                List.of("soon"),
                List.of("later")
        ));

        DataQualityColumn period = column(report, "period");
        assertEquals("DATE", period.getInferredType());
        assertEquals(2, period.getDateFormatAnomalies());
        assertHasIssue(report, "WARNING", "period", "2 values do not match the detected date format.");
    }

    @Test
    void inspect_withSingleDateAnomaly_shouldUseSingularMessage() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("period"),
                List.of("2024-01-01"),
                List.of("2024-01-02"),
                List.of("oops")
        ));

        assertHasIssue(report, "WARNING", "period", "1 value does not match the detected date format.");
    }

    @Test
    void inspect_shouldFlagNegativeMonetaryValues() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("revenue", "cost", "delta"),
                List.of("1,000", "-1", "-3"),
                List.of("-5", "2", "-4"),
                List.of("20", "3", "5")
        ));

        DataQualityColumn revenue = column(report, "revenue");
        assertEquals("NUMBER", revenue.getInferredType());
        assertEquals(1, revenue.getNegativeValueCount());
        assertEquals(-5.0, revenue.getMinimum());
        assertEquals(1000.0, revenue.getMaximum());
        assertEquals(338.333333, revenue.getAverage());
        assertHasIssue(report, "WARNING", "revenue", "1 negative monetary value was found.");

        // Negative values are only suspicious for columns that look monetary.
        assertEquals(2, column(report, "delta").getNegativeValueCount());
        assertTrue(report.getIssues().stream().noneMatch(issue -> "delta".equals(issue.getColumn())));
    }

    @Test
    void inspect_withSeveralNegativeMonetaryValues_shouldUsePluralMessage() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("price"),
                List.of("-1"),
                List.of("-2")
        ));

        assertHasIssue(report, "WARNING", "price", "2 negative monetary values were found.");
    }

    @Test
    void inspect_shouldDetectBooleanColumns() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("active", "mixed"),
                List.of("yes", "yes"),
                List.of("No", "maybe"),
                List.of("TRUE", "no"),
                List.of("false", "true")
        ));

        assertEquals("BOOLEAN", column(report, "active").getInferredType());
        assertEquals("TEXT", column(report, "mixed").getInferredType());
    }

    @Test
    void inspect_shouldReportMissingValueRatesAndKeyFieldGaps() throws Exception {
        DataQualityReport report = inspectRows(List.of(
                List.of("customer_id", "comment", "status"),
                List.of("1", "ok", "open"),
                List.of("", "fine", "open"),
                List.of("", "", "closed"),
                List.of("", "", "open"),
                // Short rows are padded with blanks rather than failing the inspection.
                List.of("")
        ));

        DataQualityColumn customerId = column(report, "customer_id");
        assertEquals(4, customerId.getNullCount());
        assertEquals(80.0, customerId.getNullRate());
        assertHasIssue(report, "ERROR", "customer_id", "More than 80% of the values are missing.");
        assertHasIssue(report, "WARNING", "customer_id", "A likely key field contains missing values.");

        assertEquals(60.0, column(report, "comment").getNullRate());
        assertHasIssue(report, "WARNING", "comment", "More than 30% of the values are missing.");

        assertEquals(20.0, column(report, "status").getNullRate());
        assertTrue(report.getIssues().stream().noneMatch(issue -> "status".equals(issue.getColumn())));
        assertEquals("NOT_RECOMMENDED", report.getRecommendation());
    }

    // endregion

    @Test
    void truncatedSpreadsheet_shouldBeFlaggedWithoutRequiringConfirmation() {
        DataQualityReport report = dataQualityService.inspect(new ExcelUtils.Spreadsheet(
                new ArrayList<>(List.of(List.of("month", "sales"), List.of("Jan", "10"))), true));

        assertTrue(report.isTruncated());
        assertHasIssue(report, "INFO", null, "The file exceeds " + ExcelUtils.MAX_DATA_ROWS + " data rows or "
                + ExcelUtils.MAX_COLUMNS + " columns; only that part was inspected.");
        assertEquals("READY", report.getRecommendation(), "a large file is not a quality problem");
    }

    private DataQualityReport inspectRows(List<List<String>> rows) {
        return dataQualityService.inspect(new ExcelUtils.Spreadsheet(rows, false));
    }

    private static DataQualityColumn column(DataQualityReport report, String name) {
        return report.getColumns().stream()
                .filter(column -> column.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No column named " + name));
    }

    private static void assertHasIssue(DataQualityReport report, String severity, String column, String message) {
        boolean found = report.getIssues().stream().anyMatch(issue -> issue.getSeverity().equals(severity)
                && Objects.equals(issue.getColumn(), column)
                && issue.getMessage().equals(message));
        assertTrue(found, () -> "Expected " + severity + " issue '" + message + "' on column " + column
                + " but got " + report.getIssues().stream().map(DataQualityIssue::toString).toList());
    }
}
