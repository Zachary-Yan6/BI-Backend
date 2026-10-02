package com.zachary.BI.exception;

import com.zachary.BI.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the handler through real Spring MVC dispatching, so framework exceptions are produced the same way
 * they are in production.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class ThrowingController {

        @GetMapping("/business")
        String business() {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Chart was not found.");
        }

        @GetMapping("/checked")
        String checked() throws IOException {
            throw new IOException("Cannot read /var/app/data/uploads/secret-key/chunk.part");
        }

        @GetMapping("/runtime")
        String runtime() {
            throw new IllegalStateException("NullPointer deep inside a mapper");
        }

        @GetMapping("/upload-too-large")
        String uploadTooLarge() {
            throw new MaxUploadSizeExceededException(10);
        }

        @GetMapping("/param")
        String param(@RequestParam int size) {
            return "ok";
        }

        @PostMapping("/json")
        String json(@RequestBody Map<String, Object> body) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessException_shouldKeepHttp200ConventionAndItsMessage() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("Chart was not found."));
    }

    @Test
    void checkedException_shouldBeHandledWithoutLeakingDetails() throws Exception {
        // Previously only RuntimeException was handled, so this fell through to Spring's default error page.
        mockMvc.perform(get("/checked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("System error"));
    }

    @Test
    void runtimeException_shouldBeHandledWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("System error"));
    }

    @Test
    void malformedJson_shouldBeAParameterErrorNotASystemError() throws Exception {
        mockMvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void missingAndInvalidParameters_shouldBeParameterErrors() throws Exception {
        mockMvc.perform(get("/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("size")));
        mockMvc.perform(get("/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void frameworkErrors_shouldKeepTheirHttpStatus() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(put("/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void oversizedUpload_shouldBePayloadTooLarge() throws Exception {
        mockMvc.perform(get("/upload-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
