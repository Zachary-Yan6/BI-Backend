package com.zachary.BI.BiMq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultParserTest {

    private static final String OPTION = "{\"xAxis\":{\"data\":[\"Jan\",\"Feb\"]},\"series\":[{\"type\":\"bar\"}]}";

    private final AnalysisResultParser parser = new AnalysisResultParser(new ObjectMapper());

    @ParameterizedTest
    @ValueSource(strings = {
            // The format the prompt asks for.
            OPTION + "\n-----\nSales grow.",
            // The most common real answer: a json fence. The old replace() left a stray "json" prefix here.
            "```json\n" + OPTION + "\n```\n-----\nSales grow.",
            "```javascript\n" + OPTION + "\n```\n-----\nSales grow.",
            // A sentence before the option, and a pretty-printed option.
            "Here is the option:\n```json\n{\n  \"xAxis\": {\"data\": [\"Jan\", \"Feb\"]},\n"
                    + "  \"series\": [{\"type\": \"bar\"}]\n}\n```\n\n-----\n\nSales grow.",
            // No separator at all: the conclusion simply follows the option.
            OPTION + "\nSales grow.",
    })
    void parse_shouldExtractCanonicalOptionAndConclusion(String answer) {
        AnalysisResultParser.AnalysisResult result = parser.parse(answer);

        assertEquals(OPTION, result.chartOption());
        assertEquals("Sales grow.", result.conclusion());
    }

    @Test
    void parse_shouldIgnoreBracesAndSeparatorsInsideStrings() {
        String option = "{\"title\":{\"text\":\"Q1 } ----- {\\\"draft\\\"}\"}}";

        AnalysisResultParser.AnalysisResult result = parser.parse(option + "\n-----\nDone.");

        assertEquals(option, result.chartOption());
        assertEquals("Done.", result.conclusion());
    }

    @Test
    void parse_shouldKeepSeparatorsThatAppearLaterInTheConclusion() {
        AnalysisResultParser.AnalysisResult result =
                parser.parse(OPTION + "\n-----\n| month | sales |\n|-----|-----|\n| Jan | 10 |");

        assertTrue(result.conclusion().startsWith("| month | sales |"));
        assertTrue(result.conclusion().contains("|-----|-----|"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // ECharts accepts JavaScript, but the frontend stores and parses JSON.
            "{\"tooltip\":{\"formatter\":function (p) { return p.name; }}}\n-----\nx",
            "{'xAxis': {}}\n-----\nx",
            "{xAxis: {}}\n-----\nx",
            "{\"xAxis\": {},}\n-----\nx",
    })
    void parse_whenOptionIsNotStrictJson_shouldReject(String answer) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> parser.parse(answer));
        assertTrue(exception.getMessage().contains("not valid JSON"), exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "Sorry, I cannot help with that.",
            "{\"xAxis\": {\"data\": [1, 2]\n-----\ntruncated",
    })
    void parse_whenNoCompleteObject_shouldReject(String answer) {
        assertThrows(IllegalStateException.class, () -> parser.parse(answer));
    }

    @Test
    void parse_whenNull_shouldReject() {
        assertThrows(IllegalStateException.class, () -> parser.parse(null));
    }

    @Test
    void parse_whenOptionIsEmpty_shouldReject() {
        assertThrows(IllegalStateException.class, () -> parser.parse("{}\n-----\nNothing to chart."));
    }

    @ParameterizedTest
    @ValueSource(strings = {OPTION, OPTION + "\n-----\n", "```json\n" + OPTION + "\n```\n-----\n   "})
    void parse_whenConclusionMissing_shouldReject(String answer) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> parser.parse(answer));
        assertTrue(exception.getMessage().contains("conclusion"));
    }
}
