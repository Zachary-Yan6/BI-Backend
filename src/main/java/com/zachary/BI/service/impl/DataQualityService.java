package com.zachary.BI.service.impl;

import com.zachary.BI.model.vo.DataQualityColumn;
import com.zachary.BI.model.vo.DataQualityIssue;
import com.zachary.BI.model.vo.DataQualityReport;
import com.zachary.BI.utils.ExcelUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DataQualityService {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("uuuu/MM/dd"),
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss")
    );

    public DataQualityReport inspect(MultipartFile file) throws IOException {
        return inspect(ExcelUtils.read(file));
    }

    /**
     * Inspects a private file that has already passed upload-session ownership checks.
     */
    public DataQualityReport inspect(
            Path filePath,
            String sourceFileType
    ) throws IOException {
        return inspect(ExcelUtils.read(filePath, sourceFileType));
    }

    /**
     * Inspects rows that were already parsed, so a caller that also needs the CSV parses the file only once.
     */
    public DataQualityReport inspect(ExcelUtils.Spreadsheet spreadsheet) {
        DataQualityReport report = inspectRows(spreadsheet.rows());
        if (spreadsheet.truncated()) {
            report.setTruncated(true);
            // INFO, not WARNING: a large file is not a quality problem and must not force a confirmation step.
            report.getIssues().add(new DataQualityIssue("INFO", null, "The file exceeds " + ExcelUtils.MAX_DATA_ROWS
                    + " data rows or " + ExcelUtils.MAX_COLUMNS + " columns; only that part was inspected."));
        }
        return report;
    }

    /**
     * Applies the same quality rules regardless of whether data arrived by multipart or file token.
     */
    private DataQualityReport inspectRows(List<List<String>> rows) {
        DataQualityReport report = new DataQualityReport();
        List<DataQualityIssue> issues = new ArrayList<>();
        report.setIssues(issues);

        if (rows.isEmpty()) {
            issues.add(new DataQualityIssue("ERROR", null, "The file does not contain a header row."));
            report.setTotalRows(0);
            report.setColumnCount(0);
            report.setDuplicateRows(0);
            report.setColumns(List.of());
            applyRecommendation(report);
            return report;
        }

        List<String> headers = new ArrayList<>(rows.getFirst());
        int columnCount = headers.size();
        int totalRows = Math.max(0, rows.size() - 1);
        report.setTotalRows(totalRows);
        report.setColumnCount(columnCount);

        if (columnCount == 0) {
            issues.add(new DataQualityIssue("ERROR", null, "The header row does not contain any columns."));
        }

        Set<String> headerNames = new HashSet<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = StringUtils.trimToEmpty(headers.get(index));
            if (header.isEmpty()) {
                header = "Column " + (index + 1);
                headers.set(index, header);
                issues.add(new DataQualityIssue("WARNING", header, "This column has no header name."));
            }
            if (!headerNames.add(header.toLowerCase(Locale.ROOT))) {
                issues.add(new DataQualityIssue("WARNING", header, "Duplicate column name detected."));
            }
        }

        if (totalRows == 0) {
            issues.add(new DataQualityIssue("ERROR", null, "The file has no data rows to analyse."));
        }

        int duplicateRows = countDuplicateRows(rows.subList(Math.min(1, rows.size()), rows.size()), columnCount);
        report.setDuplicateRows(duplicateRows);
        if (duplicateRows > 0) {
            issues.add(new DataQualityIssue("WARNING", null,
                    duplicateRows + " duplicate data row" + (duplicateRows == 1 ? " was" : "s were") + " found."));
        }

        List<DataQualityColumn> columns = new ArrayList<>(columnCount);
        for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
            DataQualityColumn column = inspectColumn(headers.get(columnIndex), columnIndex, rows, totalRows, issues);
            columns.add(column);
        }
        report.setColumns(columns);
        applyRecommendation(report);
        return report;
    }

    private DataQualityColumn inspectColumn(String name, int columnIndex, List<List<String>> rows, int totalRows,
                                             List<DataQualityIssue> issues) {
        DataQualityColumn column = new DataQualityColumn();
        column.setName(name);
        int nullCount = 0;
        int numericCount = 0;
        int dateCount = 0;
        int negativeValueCount = 0;
        List<BigDecimal> numericValues = new ArrayList<>();

        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            String value = valueAt(rows.get(rowIndex), columnIndex);
            if (StringUtils.isBlank(value)) {
                nullCount++;
                continue;
            }
            BigDecimal number = parseNumber(value);
            if (number != null) {
                numericCount++;
                numericValues.add(number);
                if (number.signum() < 0) {
                    negativeValueCount++;
                }
            }
            if (isDate(value)) {
                dateCount++;
            }
        }

        int populatedRows = totalRows - nullCount;
        column.setNullCount(nullCount);
        column.setNullRate(toPercent(nullCount, totalRows));
        column.setNegativeValueCount(negativeValueCount);
        if (populatedRows == 0) {
            column.setInferredType("EMPTY");
            issues.add(new DataQualityIssue("ERROR", name, "This column does not contain any values."));
            return column;
        }

        boolean dateColumn = dateCount > 0 && dateCount * 100 >= populatedRows * 60;
        boolean numericColumn = numericCount * 100 >= populatedRows * 80;
        if (dateColumn) {
            column.setInferredType("DATE");
            int dateAnomalies = populatedRows - dateCount;
            column.setDateFormatAnomalies(dateAnomalies);
            if (dateAnomalies > 0) {
                issues.add(new DataQualityIssue("WARNING", name,
                        dateAnomalies + " value" + (dateAnomalies == 1 ? " does" : "s do") + " not match the detected date format."));
            }
        } else if (numericColumn) {
            column.setInferredType("NUMBER");
            setNumericSummary(column, numericValues);
            if (isMoneyColumn(name) && negativeValueCount > 0) {
                issues.add(new DataQualityIssue("WARNING", name,
                        negativeValueCount + " negative monetary value" + (negativeValueCount == 1 ? " was" : "s were") + " found."));
            }
        } else if (isBooleanColumn(populatedRows, rows, columnIndex)) {
            column.setInferredType("BOOLEAN");
        } else {
            column.setInferredType("TEXT");
        }

        if (column.getNullRate() >= 80) {
            issues.add(new DataQualityIssue("ERROR", name, "More than 80% of the values are missing."));
        } else if (column.getNullRate() >= 30) {
            issues.add(new DataQualityIssue("WARNING", name, "More than 30% of the values are missing."));
        }
        if (isKeyColumn(name) && nullCount > 0) {
            issues.add(new DataQualityIssue("WARNING", name, "A likely key field contains missing values."));
        }
        return column;
    }

    private void setNumericSummary(DataQualityColumn column, List<BigDecimal> values) {
        if (values.isEmpty()) {
            return;
        }
        BigDecimal minimum = values.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maximum = values.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
        column.setMinimum(minimum.doubleValue());
        column.setMaximum(maximum.doubleValue());
        column.setAverage(average.doubleValue());
    }

    private int countDuplicateRows(List<List<String>> rows, int columnCount) {
        Set<String> uniqueRows = new HashSet<>();
        int duplicateRows = 0;
        for (List<String> row : rows) {
            StringBuilder key = new StringBuilder();
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                key.append(StringUtils.trimToEmpty(valueAt(row, columnIndex))).append('\u001F');
            }
            if (!uniqueRows.add(key.toString())) {
                duplicateRows++;
            }
        }
        return duplicateRows;
    }

    private void applyRecommendation(DataQualityReport report) {
        boolean hasError = report.getIssues().stream().anyMatch(issue -> "ERROR".equals(issue.getSeverity()));
        boolean hasWarning = report.getIssues().stream().anyMatch(issue -> "WARNING".equals(issue.getSeverity()));
        if (hasError) {
            report.setRecommendation("NOT_RECOMMENDED");
            report.setConfirmationRequired(true);
        } else if (hasWarning) {
            report.setRecommendation("REVIEW_REQUIRED");
            report.setConfirmationRequired(true);
        } else {
            report.setRecommendation("READY");
            report.setConfirmationRequired(false);
        }
    }

    private String valueAt(List<String> row, int columnIndex) {
        return columnIndex < row.size() ? StringUtils.defaultString(row.get(columnIndex)) : "";
    }

    private BigDecimal parseNumber(String value) {
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isDate(String value) {
        String candidate = value.trim();
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                if (candidate.contains(":")) {
                    LocalDateTime.parse(candidate, formatter);
                } else {
                    LocalDate.parse(candidate, formatter);
                }
                return true;
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        return false;
    }

    private boolean isBooleanColumn(int populatedRows, List<List<String>> rows, int columnIndex) {
        int booleanCount = 0;
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            String value = StringUtils.trimToEmpty(valueAt(rows.get(rowIndex), columnIndex)).toLowerCase(Locale.ROOT);
            if (value.isEmpty() || value.equals("true") || value.equals("false") || value.equals("yes") || value.equals("no")) {
                booleanCount++;
            }
        }
        return populatedRows > 0 && booleanCount == rows.size() - 1;
    }

    private boolean isMoneyColumn(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(amount|price|cost|revenue|sales|income|expense|payment|fee|balance|total).*" );
    }

    private boolean isKeyColumn(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.matches(".*(id|name|date|amount|email|account).*" );
    }

    private double toPercent(int part, int total) {
        if (total == 0) {
            return 0;
        }
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
