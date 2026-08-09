package io.agentscope.e2e.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.e2e.support.E2eTestSupport;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StreamingIT extends E2eTestSupport {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    @Test
    @Timeout(60)
    void shouldStreamModelOutputThroughAgent() {
        ReActAgent agent = createAgent("Reply briefly in plain text.");
        List<Msg> chunks = new CopyOnWriteArrayList<>();

        // ReActAgent exposes streaming through its reactive call publisher; it does not have a
        // separate stream(...) entry point. Subscribing without collapsing the publisher lets
        // this test observe each message emitted while Ollama is streaming.
        agent.call(List.of(new UserMessage("Write the numbers 1, 2, and 3.")))
                .doOnNext(chunks::add)
                .then()
                .block();

        assertFalse(chunks.isEmpty(), "stream must emit at least one message");
        assertTrue(chunks.stream()
                .map(Msg::getTextContent)
                .anyMatch(text -> text != null && !text.isBlank()),
                "stream must contain at least one text delta");
    }

    @Test
    @Timeout(60)
    void shouldPreserveRequestedMarkerWhenCombiningStreamedText() {
        String token = "STREAM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String expectedReply = "STREAM_OK=" + token;
        ReActAgent agent = createAgent("Reply only with this exact text: " + expectedReply);
        List<String> textEvents = new CopyOnWriteArrayList<>();

        agent.call(List.of(new UserMessage("Reply only with this exact text: " + expectedReply)))
                .map(Msg::getTextContent)
                .filter(text -> text != null && !text.isBlank())
                .doOnNext(textEvents::add)
                .then()
                .block();

        assertFalse(textEvents.isEmpty(), "stream must emit at least one non-empty text event");
        String combined = String.join("", textEvents);
        assertTrue(combined.contains("STREAM_OK"),
                () -> "Combined stream lost the marker key: " + combined);
        assertTrue(combined.contains(token),
                () -> "Combined stream lost the requested token: " + combined);
    }

    @Test
    @Timeout(60)
    void shouldEmitContentBeforeOverallCompletion() {
        ReActAgent agent = createAgent("Reply with the requested marker 20 times, separated by spaces.");
        String marker = "INCREMENTAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AtomicLong firstIncrementalEventAt = new AtomicLong(-1);
        AtomicLong completionAt = new AtomicLong(-1);

        List<AgentEvent> events = agent.streamEvents(
                        List.of(new UserMessage("Reply with " + marker + " exactly 20 times.")))
                .doOnNext(event -> {
                    if (hasTextDelta(event)) {
                        firstIncrementalEventAt.compareAndSet(-1, System.nanoTime());
                    }
                })
                .doOnComplete(() -> completionAt.set(System.nanoTime()))
                .collectList()
                .block(CALL_TIMEOUT);

        assertNotNull(events, "stream must complete with observable events");
        long textDeltaCount = events.stream().filter(this::hasTextDelta).count();
        assertTrue(textDeltaCount > 1,
                () -> "streaming must emit multiple non-empty text delta events, but emitted "
                        + textDeltaCount);
        assertTrue(firstIncrementalEventAt.get() > 0,
                "first incremental text event timestamp was not recorded");
        assertTrue(completionAt.get() > firstIncrementalEventAt.get(),
                "incremental text must be observable before the overall stream completes");
    }

    @Test
    @Timeout(30)
    void shouldStopEmittingAfterSubscriptionCancellation() throws Exception {
        AtomicBoolean upstreamCancelled = new AtomicBoolean();

        try (CancellationOllamaServer server = new CancellationOllamaServer()) {
            ReActAgent agent = createCancellationAgent(server);
            List<AgentEvent> firstEvents = agent.streamEvents(
                            List.of(new UserMessage("Start the cancellable response.")))
                    .doOnCancel(() -> upstreamCancelled.set(true))
                    .filter(this::hasTextDelta)
                    .take(1)
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertNotNull(firstEvents, "cancellation test must observe streamed content");
            assertEquals(1, firstEvents.size(),
                    "subscriber must receive exactly one text delta before cancellation");
            assertTrue(upstreamCancelled.get(), "taking one event must cancel the upstream stream");
            assertTrue(server.awaitFirstRequestReleased(Duration.ofSeconds(5)),
                    "cancelled subscription must promptly release the streaming HTTP request");

            Msg followUp = agent.call(List.of(new UserMessage("Reply only FOLLOWUP_OK.")))
                    .block(Duration.ofSeconds(10));
            assertNotNull(followUp,
                    "agent must remain usable after a streaming subscription is cancelled");
            assertTrue(followUp.getTextContent().contains("FOLLOWUP_OK"), followUp::getTextContent);
        }
    }

    @Test
    @Timeout(60)
    void shouldSignalErrorWhenStreamingServiceIsUnavailable() {
        String modelName = MODEL_ID.startsWith("ollama:")
                ? MODEL_ID.substring("ollama:".length())
                : MODEL_ID;
        OllamaChatModel unavailableModel = OllamaChatModel.builder()
                .modelName(modelName)
                .baseUrl("http://127.0.0.1:1")
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("unavailable-stream-e2e-agent")
                .sysPrompt("Reply briefly.")
                .model(unavailableModel)
                .build();
        AtomicBoolean completed = new AtomicBoolean();

        assertThrows(RuntimeException.class, () -> agent.call(List.of(new UserMessage("Reply OK")))
                .doOnSuccess(ignored -> completed.set(true))
                .then()
                .block(), "an unavailable streaming service must terminate with an error");

        assertFalse(completed.get(), "an unavailable streaming service must not complete successfully");
    }

    private boolean hasTextDelta(AgentEvent event) {
        return event instanceof TextBlockDeltaEvent textDelta
                && textDelta.getDelta() != null
                && !textDelta.getDelta().isBlank();
    }

    private ReActAgent createCancellationAgent(CancellationOllamaServer server) {
        OllamaChatModel model = OllamaChatModel.builder()
                .modelName("cancellation-model")
                .baseUrl(server.baseUrl())
                .build();
        return ReActAgent.builder()
                .name("cancellation-stream-e2e-agent")
                .sysPrompt("Reply using the requested text.")
                .model(model)
                .build();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static final class CancellationOllamaServer implements AutoCloseable {
        private static final String FOLLOW_UP_RESPONSE = """
                {"model":"cancellation-model","message":{"role":"assistant","content":"FOLLOWUP_OK"},"done":true}
                """;
        private static final byte[] STREAM_CHUNK = ("""
                {"model":"cancellation-model","message":{"role":"assistant","content":"CANCEL_PART_%s"},"done":false}
                """.formatted("x".repeat(8_192))).getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final AtomicInteger requests = new AtomicInteger();
        private final CountDownLatch firstRequestReleased = new CountDownLatch(1);

        private CancellationOllamaServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            if (requests.incrementAndGet() == 1) {
                streamUntilCancelled(exchange);
            } else {
                respond(exchange, FOLLOW_UP_RESPONSE);
            }
        }

        private void streamUntilCancelled(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                while (true) {
                    body.write(STREAM_CHUNK);
                    body.flush();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (IOException cancelledConnection) {
                // The streaming client closes the response body when its subscription is cancelled.
            } finally {
                firstRequestReleased.countDown();
                exchange.close();
            }
        }

        private boolean awaitFirstRequestReleased(Duration timeout) throws InterruptedException {
            return firstRequestReleased.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
