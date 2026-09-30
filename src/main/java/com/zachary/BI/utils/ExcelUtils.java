package com.zachary.BI.utils;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ExcelUtils {

    public static String excelToCsv(MultipartFile multipartFile) throws IOException {
        return toCsv(readSpreadsheet(multipartFile));
    }

    public static String excelToCsv(
            Path filePath,
            String sourceFileType
    ) throws IOException {
        return toCsv(readSpreadsheet(filePath, sourceFileType));
    }

    public static List<List<String>> readSpreadsheet(
            MultipartFile multipartFile
    ) throws IOException {
        String sourceFileType = Optional.ofNullable(multipartFile.getOriginalFilename())
                .map(ExcelUtils::getFileSuffix)
                .orElse("");

        try (InputStream inputStream = multipartFile.getInputStream()) {
            return readSpreadsheet(inputStream, sourceFileType);
        }
    }

    public static List<List<String>> readSpreadsheet(
            Path filePath,
            String sourceFileType
    ) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath)) {
            return readSpreadsheet(inputStream, sourceFileType);
        }
    }

    private static String escapeCsv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static List<List<String>> readSpreadsheet(
            InputStream inputStream,
            String sourceFileType
    ) throws IOException {
        ExcelTypeEnum excelType = switch (sourceFileType.toLowerCase(Locale.ROOT)) {
            case "csv" -> ExcelTypeEnum.CSV;
            case "xlsx" -> ExcelTypeEnum.XLSX;
            default -> throw new IOException("Unsupported spreadsheet format.");
        };

        List<Map<Integer, String>> list = EasyExcel.read(inputStream)
                .excelType(excelType)
                .sheet()
                .headRowNumber(0)
                .doReadSync();

        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }

        int columnCount = list.stream()
                .flatMap(row -> row.keySet().stream())
                .max(Integer::compareTo)
                .map(index -> index + 1)
                .orElse(0);

        List<List<String>> rows = new ArrayList<>(list.size());

        for (Map<Integer, String> sourceRow : list) {
            List<String> row = new ArrayList<>(columnCount);

            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                row.add(Objects.toString(sourceRow.get(columnIndex), ""));
            }

            rows.add(row);
        }

        return rows;
    }

    private static String toCsv(List<List<String>> rows) {
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

    private static String getFileSuffix(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex < 0
                ? ""
                : fileName.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
