package com.zachary.BI;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs GenAi against a real local HTTP server, because timeouts are behaviour of the HTTP client, not of our code.
 */
class GenAiTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);
    /** Generous upper bound: if the timeout did not work, these tests would hang far longer than this. */
    private static final Duration TEST_DEADLINE = Duration.ofSeconds(10);

    private HttpServer server;
    private ExecutorService serverThreads;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void doChat_shouldReturnMessageContent() throws Exception {
        start(exchange -> respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"chart-----result\"}}]}"));

        assertEquals("chart-----result", genAi("key").doChat("hello"));
    }

    @Test
    void doChat_whenProviderNeverResponds_shouldTimeOut() throws Exception {
        start(exchange -> sleepQuietly(Duration.ofSeconds(30)));

        assertTimeoutPreemptively(TEST_DEADLINE,
                () -> assertThrows(RuntimeException.class, () -> genAi("key").doChat("hello")));
    }

    @Test
    void doChat_whenProviderSendsHeadersThenKeepsConnectionAlive_shouldStillTimeOut() throws Exception {
        // DeepSeek's non-streaming API sends headers early and then blank lines while it generates.
        // A timeout that only covered "waiting for headers" would never fire here.
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (int i = 0; i < 300; i++) {
                    body.write('\n');
                    body.flush();
                    sleepQuietly(Duration.ofMillis(100));
                }
            } catch (IOException clientWentAway) {
                // Expected once the client gives up.
            }
        });

        long started = System.nanoTime();
        assertTimeoutPreemptively(TEST_DEADLINE,
                () -> assertThrows(RuntimeException.class, () -> genAi("key").doChat("hello")));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(READ_TIMEOUT) >= 0, "gave up only after the configured timeout: " + elapsed);
    }

    @Test
    void doChat_withoutApiKey_shouldFailFastWithoutCallingProvider() {
        GenAi genAi = new GenAi(RestClient.builder(), new ObjectMapper(), "http://127.0.0.1:1", "", "model",
                Duration.ofSeconds(1), READ_TIMEOUT);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> genAi.doChat("hello"));
        assertTrue(exception.getMessage().contains("DEEPSEEK_API_KEY"));
    }

    @Test
    void doChat_whenResponseHasNoContent_shouldFail() throws Exception {
        start(exchange -> respond(exchange, "{\"choices\":[]}"));

        assertThrows(IllegalStateException.class, () -> genAi("key").doChat("hello"));
    }

    private GenAi genAi(String apiKey) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GenAi(RestClient.builder(), new ObjectMapper(), baseUrl, apiKey, "model",
                Duration.ofSeconds(1), READ_TIMEOUT);
    }

    private void start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/chat/completions", handler);
        server.start();
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
