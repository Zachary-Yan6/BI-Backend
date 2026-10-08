package com.zachary.BI.utils;

import com.alibaba.excel.EasyExcel;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

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
    void read_beyondTheCellBudget_shouldStopAtTheLastRowThatFits() throws Exception {
        // 100 one-digit cells per row: before the cell budget this shape held about 125 MB for a 5 MB file.
        String row = IntStream.range(0, 100).mapToObj(i -> String.valueOf(i % 10)).collect(Collectors.joining(","));
        int rowsThatFit = ExcelUtils.MAX_CELLS / 100;
        Path csv = write((row + "\n").repeat(rowsThatFit + 5));

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csv, "csv");

        assertEquals(rowsThatFit, spreadsheet.rows().size());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_longCell_shouldBeCutToTheCellLimit() throws Exception {
        Path csv = write("label,value\n" + "x".repeat(ExcelUtils.MAX_CELL_CHARS + 500) + ",1\n");

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csv, "csv");

        assertEquals(ExcelUtils.MAX_CELL_CHARS, spreadsheet.rows().get(1).get(0).length());
        assertEquals("1", spreadsheet.rows().get(1).get(1));
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_beyondTheCharacterBudget_shouldStopAtTheLastRowThatFits() throws Exception {
        String longCell = "x".repeat(ExcelUtils.MAX_CELL_CHARS);
        int rowsThatFit = ExcelUtils.MAX_TOTAL_CHARS / ExcelUtils.MAX_CELL_CHARS;
        Path csv = write((longCell + "\n").repeat(rowsThatFit + 3));

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(csv, "csv");

        assertEquals(rowsThatFit, spreadsheet.rows().size());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_xlsxWhoseSheetExpandsBeyondTheEntryLimit_shouldBeRejected() throws Exception {
        // About 42:1, well under POI's 100:1 zip-bomb ratio, yet one cell would hold more than the entry limit.
        // Before the limit, a 2.3 MB file like this held about 234 MB of heap.
        Path xlsx = xlsxWithOneInlineCell(ExcelUtils.MAX_XLSX_ENTRY_BYTES + 1024 * 1024);

        Exception rejected = assertThrows(Exception.class, () -> ExcelUtils.read(xlsx, "xlsx"));

        assertTrue(ExceptionUtils.getRootCauseMessage(rejected).contains("Zip bomb detected"),
                ExceptionUtils.getRootCauseMessage(rejected));
    }

    @Test
    void read_xlsxWhoseSheetIsLargerThanTheEntryLimit_shouldStillReadItsFirstPart() throws Exception {
        // POI counts bytes as they are read, so a big but ordinary sheet stops at the cell budget long before the
        // entry limit is reached.
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 0; r < ExcelUtils.MAX_CELLS / 100 + 5; r++) {
            List<Object> row = new ArrayList<>(100);
            for (int c = 0; c < 100; c++) {
                row.add(c % 10);
            }
            rows.add(row);
        }
        Path xlsx = tempDir.resolve("wide.xlsx");
        EasyExcel.write(xlsx.toFile()).sheet("data").doWrite(rows);

        ExcelUtils.Spreadsheet spreadsheet = ExcelUtils.read(xlsx, "xlsx");

        assertEquals(ExcelUtils.MAX_CELLS / 100, spreadsheet.rows().size());
        assertTrue(spreadsheet.truncated());
    }

    @Test
    void read_unsupportedType_shouldFail() throws Exception {
        Path file = write("x");

        assertThrows(IOException.class, () -> ExcelUtils.read(file, "pdf"));
    }

    /**
     * Builds a workbook whose first sheet is one inline-string cell of the given size, streaming it so the test
     * itself never holds the text in memory.
     */
    private Path xlsxWithOneInlineCell(long cellChars) throws IOException {
        Path template = tempDir.resolve("template.xlsx");
        EasyExcel.write(template.toFile()).sheet("data").doWrite(List.of(List.of("x")));
        Path xlsx = tempDir.resolve("expanding.xlsx");
        byte[] chunk = new byte[1024 * 1024];
        Arrays.fill(chunk, (byte) 'a');
        for (int i = 0; i < chunk.length; i += 60) {
            chunk[i] = (byte) ('a' + i % 26);
        }
        try (ZipFile source = new ZipFile(template.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(xlsx))) {
            for (ZipEntry entry : Collections.list(source.entries())) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    out.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns="
                            + "\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\">"
                            + "<c r=\"A1\" t=\"inlineStr\"><is><t>").getBytes(StandardCharsets.UTF_8));
                    for (long written = 0; written < cellChars; written += chunk.length) {
                        out.write(chunk);
                    }
                    out.write("</t></is></c></row></sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream in = source.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        }
        return xlsx;
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
