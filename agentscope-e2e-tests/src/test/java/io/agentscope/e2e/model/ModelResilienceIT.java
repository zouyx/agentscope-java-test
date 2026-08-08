package io.agentscope.e2e.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ModelResilienceIT {
    private static final String SUCCESS_RESPONSE = """
            {"model":"resilience-model","message":{"role":"assistant","content":"RESULT=OK"},"done":true}
            """;

    @Test
    @Timeout(15)
    void shouldRespectRetryAfterForRateLimitedModelRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicLong firstRequestAt = new AtomicLong();
        AtomicLong secondRequestAt = new AtomicLong();
        try (StubOllamaServer server = new StubOllamaServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                firstRequestAt.set(System.nanoTime());
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "{\"error\":\"rate limit exceeded\"}");
            } else {
                secondRequestAt.compareAndSet(0L, System.nanoTime());
                respond(exchange, 200, SUCCESS_RESPONSE);
            }
        })) {
            ReActAgent agent = createAgent(server, ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(10))
                    .maxAttempts(2)
                    .initialBackoff(Duration.ofMillis(10))
                    .maxBackoff(Duration.ofMillis(10))
                    .build());

            Msg result = call(agent);
            Duration retryDelay = Duration.ofNanos(secondRequestAt.get() - firstRequestAt.get());

            assertSuccessfulResult(result);
            assertEquals(2, requests.get(), "one rate-limited request should produce one retry");
            assertTrue(firstRequestAt.get() > 0 && secondRequestAt.get() > 0,
                    "test must observe both first and second request timestamps");
            assertTrue(retryDelay.compareTo(Duration.ofMillis(900)) >= 0,
                    () -> "retry occurred before the Retry-After interval: " + retryDelay);
        }
    }

    @Test
    @Timeout(15)
    void shouldBoundRetriesForTransientModelFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (StubOllamaServer server = new StubOllamaServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"temporary model outage\"}");
        })) {
            ReActAgent agent = createAgent(server, ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(10))
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofMillis(50))
                    .maxBackoff(Duration.ofMillis(50))
                    .build());

            RuntimeException error = assertThrows(RuntimeException.class, () -> call(agent),
                    "exhausted transient failures must not become an empty success");

            assertEquals(3, requests.get(), "maxAttempts must bound total model requests");
            String diagnostics = causeChain(error);
            assertTrue(diagnostics.contains("503") || diagnostics.contains("temporary model outage"),
                    () -> "failure should retain an HTTP/service diagnostic: " + diagnostics);
        }
    }

    private ReActAgent createAgent(StubOllamaServer server, ExecutionConfig executionConfig) {
        OllamaChatModel model = OllamaChatModel.builder()
                .modelName("resilience-model")
                .baseUrl(server.baseUrl())
                .build();
        return ReActAgent.builder()
                .name("model-resilience-agent")
                .sysPrompt("Reply using the requested result protocol.")
                .model(model)
                .modelExecutionConfig(executionConfig)
                .build();
    }

    private Msg call(ReActAgent agent) {
        return agent.call(List.of(new UserMessage("Reply RESULT=OK"))).block();
    }

    private void assertSuccessfulResult(Msg result) {
        assertNotNull(result, "a recovered model request must emit a result");
        String text = result.getTextContent();
        assertNotNull(text, "a recovered model request must contain text");
        assertFalse(text.isBlank(), "a recovered model request must not contain blank text");
        assertTrue(text.contains("RESULT=OK"), () -> "unexpected recovered response: " + text);
    }

    private String causeChain(Throwable error) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = error; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getClass().getSimpleName())
                        .append(": ")
                        .append(current.getMessage())
                        .append('\n');
            }
        }
        return messages.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private static final class StubOllamaServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        private StubOllamaServer(Responder responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> responder.respond(exchange));
            server.setExecutor(executor);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
