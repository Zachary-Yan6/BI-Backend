package com.zachary.BI.BiMq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Turns the model's free-form answer into a validated ECharts option and a conclusion.
 * <p>
 * The model is asked for "JSON, then -----, then the conclusion", but in practice wraps the JSON in
 * {@code ```json} or {@code ```javascript} fences, adds a sentence before it, or emits JavaScript that is not
 * JSON. Instead of string replacement this locates the first complete JSON object, parses it, and rejects
 * anything invalid so the job is retried rather than stored as a chart the frontend cannot render.
 */
@Component
public class AnalysisResultParser {

    /** Leading closing fence and/or separator line(s) between the JSON object and the conclusion. */
    private static final Pattern LEADING_FENCE_OR_SEPARATOR = Pattern.compile("^(\\s*(```|-{3,}))+\\s*");

    private final ObjectMapper objectMapper;

    public AnalysisResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record AnalysisResult(String chartOption, String conclusion) {
    }

    /**
     * @throws IllegalStateException when the answer has no valid JSON object or no conclusion; the caller
     *                               treats this like any other AI failure and retries the job
     */
    public AnalysisResult parse(String answer) {
        String text = StringUtils.defaultString(answer);
        int start = text.indexOf('{');
        int end = start < 0 ? -1 : findMatchingBrace(text, start);
        if (end < 0) {
            throw new IllegalStateException("AI result does not contain a complete JSON object");
        }

        JsonNode option;
        try {
            option = objectMapper.readTree(text.substring(start, end + 1));
        } catch (JsonProcessingException exception) {
            // Typical causes: JavaScript functions, single quotes, unquoted keys, trailing commas.
            throw new IllegalStateException("AI chart option is not valid JSON: " + exception.getOriginalMessage());
        }
        if (!option.isObject() || option.isEmpty()) {
            throw new IllegalStateException("AI chart option must be a non-empty JSON object");
        }

        String conclusion = LEADING_FENCE_OR_SEPARATOR.matcher(text.substring(end + 1)).replaceFirst("").trim();
        if (conclusion.isEmpty()) {
            throw new IllegalStateException("AI result does not contain an analysis conclusion");
        }

        try {
            // Store the canonical form: compact, and exactly what the frontend's JSON.parse will accept.
            return new AnalysisResult(objectMapper.writeValueAsString(option), conclusion);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialise the AI chart option", exception);
        }
    }

    /**
     * Returns the index of the brace closing the object that opens at {@code start}, or -1.
     * Braces inside string literals (including escaped quotes) are ignored.
     */
    private static int findMatchingBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
