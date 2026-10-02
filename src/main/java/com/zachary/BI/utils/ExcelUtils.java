package com.zachary.BI.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class ExcelUtils {

    /**
     * Data rows kept after the header. The AI only ever receives about 60 KB of the CSV, so reading millions of
     * rows bought nothing but memory: a 25 MB CSV used to occupy roughly 240 MB of heap once parsed.
     */
    public static final int MAX_DATA_ROWS = 50_000;

    /** Columns kept per row; anything wider is not a table a chart can use. */
    public static final int MAX_COLUMNS = 100;

    /**
     * Parsed rows (header first) padded to the same width.
     *
     * @param truncated true when the file had more rows or columns than the limits and the rest was not read
     */
    public record Spreadsheet(List<List<String>> rows, boolean truncated) {
    }

    public static Spreadsheet read(MultipartFile multipartFile) throws IOException {
        String sourceFileType = Optional.ofNullable(multipartFile.getOriginalFilename())
                .map(ExcelUtils::getFileSuffix)
                .orElse("");

        try (InputStream inputStream = multipartFile.getInputStream()) {
            return read(inputStream, sourceFileType);
        }
    }

    public static Spreadsheet read(Path filePath, String sourceFileType) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            return read(inputStream, sourceFileType);
        }
    }

    public static String toCsv(List<List<String>> rows) {
        StringBuilder csv = new StringBuilder();

        for (List<String> row : rows) {
            csv.append(
                    row.stream()
                            .map(ExcelUtils::escapeCsv)
                            .collect(Collectors.joining(","))
            ).append('\n');
        }

        return csv.toString();
    }

    private static Spreadsheet read(InputStream inputStream, String sourceFileType) throws IOException {
        ExcelTypeEnum excelType = switch (sourceFileType.toLowerCase(Locale.ROOT)) {
            case "csv" -> ExcelTypeEnum.CSV;
            case "xlsx" -> ExcelTypeEnum.XLSX;
            default -> throw new IOException("Unsupported spreadsheet format.");
        };

        BoundedRowCollector collector = new BoundedRowCollector();
        // Streaming read: each row is converted as it arrives instead of materialising the whole sheet first.
        EasyExcel.read(inputStream, collector)
                .excelType(excelType)
                .sheet()
                .headRowNumber(0)
                .doRead();
        return collector.toSpreadsheet();
    }

    /**
     * Collects rows until the limits are reached, then tells EasyExcel to stop parsing the rest of the file.
     */
    private static final class BoundedRowCollector implements ReadListener<Map<Integer, String>> {
        private final List<List<String>> rows = new ArrayList<>();
        private int columnCount;
        private boolean truncated;

        @Override
        public void invoke(Map<Integer, String> cells, AnalysisContext context) {
            if (rows.size() > MAX_DATA_ROWS) {
                // Header plus MAX_DATA_ROWS are already kept; this extra row only proves the file was longer.
                truncated = true;
                return;
            }
            int width = cells.keySet().stream().max(Integer::compareTo).map(index -> index + 1).orElse(0);
            if (width > MAX_COLUMNS) {
                truncated = true;
                width = MAX_COLUMNS;
            }
            List<String> row = new ArrayList<>(width);
            for (int columnIndex = 0; columnIndex < width; columnIndex++) {
                row.add(Objects.toString(cells.get(columnIndex), ""));
            }
            rows.add(row);
            columnCount = Math.max(columnCount, width);
        }

        @Override
        public boolean hasNext(AnalysisContext context) {
            return !(truncated && rows.size() > MAX_DATA_ROWS);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
        }

        Spreadsheet toSpreadsheet() {
            for (List<String> row : rows) {
                while (row.size() < columnCount) {
                    row.add("");
                }
            }
            return new Spreadsheet(rows, truncated);
        }
    }

    private static String escapeCsv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String getFileSuffix(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex < 0
                ? ""
                : fileName.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
