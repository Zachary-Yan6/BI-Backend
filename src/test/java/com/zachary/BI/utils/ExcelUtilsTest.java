package com.zachary.BI.utils;

import com.alibaba.excel.EasyExcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void read_shouldConvertCsvUploadWithoutTruncation() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sales.csv", "text/csv",
                "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8));

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(file);

        assertEquals("month,sales\nJan,10\nFeb,12\n", ExcelUtils.toCsv(spreadsheet.rows()));
        assertFalse(spreadsheet.truncated());
    }

    @Test
    void read_shouldPadRaggedRowsAndEscapeCsvValues() throws Exception {
        Path csv = write("a,b,c\n1\n\"x, y\",\"say \"\"hi\"\"\",3\n");

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csv, "csv");

        assertEquals(List.of("1", "", ""), spreadsheet.rows().get(1));
        assertEquals("a,b,c\n1,,\n\"x, y\",\"say \"\"hi\"\"\",3\n", ExcelUtils.toCsv(spreadsheet.rows()));
    }

    @Test
    void read_withExactlyTheRowLimit_shouldNotBeTruncated() throws Exception {
        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csvWithDataRows(ExcelUtils.MAX_DATA_ROWS), "csv");

        assertEquals(ExcelUtils.MAX_DATA_ROWS + 1, spreadsheet.rows().size());
        assertFalse(spreadsheet.truncated());
    }

    @Test
    void read_beyondTheRowLimit_shouldKeepTheLimitAndReportTruncation() throws Exception {
        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csvWithDataRows(ExcelUtils.MAX_DATA_ROWS + 500), "csv");

        assertEquals(ExcelUtils.MAX_DATA_ROWS + 1, spreadsheet.rows().size(), "header plus the row limit");
        assertEquals(List.of("row" + ExcelUtils.MAX_DATA_ROWS, "1"), spreadsheet.rows().getLast());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_beyondTheColumnLimit_shouldDropExtraColumns() throws Exception {
        String header = IntStream.range(0, ExcelUtils.MAX_COLUMNS + 5).mapToObj(i -> "c" + i)
                .collect(Collectors.joining(","));

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(write(header + "\n"), "csv");

        assertEquals(ExcelUtils.MAX_COLUMNS, spreadsheet.rows().getFirst().size());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_xlsxBeyondTheRowLimit_shouldStopEarly() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("name", "value"));
        for (int i = 1; i <= ExcelUtils.MAX_DATA_ROWS + 10; i++) {
            rows.add(List.of("row" + i, String.valueOf(i)));
        }
        Path xlsx = tempDir.resolve("big.xlsx");
        EasyExcel.write(xlsx.toFile()).sheet("data").doWrite(rows);

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(xlsx, "xlsx");

        assertEquals(ExcelUtils.MAX_DATA_ROWS + 1, spreadsheet.rows().size());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_unsupportedType_shouldFail() throws Exception {
        Path file = write("x");

        assertThrows(IOException.class, () -> ExcelUtils.read(file, "pdf"));
    }

    private Path csvWithDataRows(int dataRows) throws IOException {
        StringBuilder csv = new StringBuilder("name,value\n");
        for (int i = 1; i <= dataRows; i++) {
            csv.append("row").append(i).append(",1\n");
        }
        return write(csv.toString());
    }

    private Path write(String content) throws IOException {
        return Files.writeString(Files.createTempFile(tempDir, "sheet", ".csv"), content, StandardCharsets.UTF_8);
    }
}
