package com.zachary.BI.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.apache.poi.openxml4j.util.ZipSecureFile;
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
     * Cells kept in total. Every cell is its own String of about 50 bytes however short its text, so the row and
     * column limits alone still allowed 5 million cells: a 5 MB CSV of one-digit cells held about 125 MB of heap.
     */
    public static final int MAX_CELLS = 1_000_000;

    /** Characters kept per cell; a chart needs labels and numbers, not documents. */
    public static final int MAX_CELL_CHARS = 1_000;

    /** Characters kept in total, after per-cell truncation. The AI receives about 60 KB of it. */
    public static final int MAX_TOTAL_CHARS = 4_000_000;

    /**
     * Largest uncompressed part of an XLSX that POI will read. Its zip-bomb check only limits the compression
     * ratio (100:1), so a 2.3 MB file at 42:1 could still put 100 million characters into one cell, more than any
     * of the limits above can stop because the parser builds a whole cell before handing it over. POI counts bytes
     * as they are read, so a normal file that hits the limits above stops long before this.
     */
    static final long MAX_XLSX_ENTRY_BYTES = 64L * 1024 * 1024;

    static {
        ZipSecureFile.setMaxEntrySize(MAX_XLSX_ENTRY_BYTES);
    }

    /**
     * Parsed rows (header first) padded to the same width.
     *
     * @param truncated true when the file exceeded a limit above and only its first part was kept
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
     * Collects rows until a limit is reached, then tells EasyExcel to stop parsing the rest of the file.
     */
    private static final class BoundedRowCollector implements ReadListener<Map<Integer, String>> {
        private final List<List<String>> rows = new ArrayList<>();
        private int columnCount;
        private long cellCount;
        private long charCount;
        private boolean truncated;
        private boolean full;

        @Override
        public void invoke(Map<Integer, String> cells, AnalysisContext context) {
            if (full) {
                return;
            }
            if (rows.size() > MAX_DATA_ROWS) {
                // Header plus MAX_DATA_ROWS are already kept; this extra row only proves the file was longer.
                stop();
                return;
            }
            int width = cells.keySet().stream().max(Integer::compareTo).map(index -> index + 1).orElse(0);
            if (width > MAX_COLUMNS) {
                truncated = true;
                width = MAX_COLUMNS;
            }
            if (cellCount + width > MAX_CELLS) {
                stop();
                return;
            }
            List<String> row = new ArrayList<>(width);
            long rowChars = 0;
            for (int columnIndex = 0; columnIndex < width; columnIndex++) {
                String value = Objects.toString(cells.get(columnIndex), "");
                if (value.length() > MAX_CELL_CHARS) {
                    truncated = true;
                    value = value.substring(0, MAX_CELL_CHARS);
                }
                row.add(value);
                rowChars += value.length();
            }
            if (charCount + rowChars > MAX_TOTAL_CHARS) {
                stop();
                return;
            }
            rows.add(row);
            cellCount += width;
            charCount += rowChars;
            columnCount = Math.max(columnCount, width);
        }

        private void stop() {
            truncated = true;
            full = true;
        }

        @Override
        public boolean hasNext(AnalysisContext context) {
            return !full;
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
