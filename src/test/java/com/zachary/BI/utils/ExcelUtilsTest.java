package com.zachary.BI.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ExcelUtilsTest {

    @Test
    void convertsCsvUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sales.csv",
                "text/csv",
                "month,sales\nJan,10\nFeb,12\n".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals("month,sales\nJan,10\nFeb,12\n", ExcelUtils.excelToCsv(file));
    }
}
