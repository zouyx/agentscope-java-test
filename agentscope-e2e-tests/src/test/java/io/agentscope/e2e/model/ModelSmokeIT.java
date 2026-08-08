package io.agentscope.e2e.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.e2e.support.E2eTestSupport;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ModelSmokeIT extends E2eTestSupport {
    @Test
    @Timeout(60)
    void shouldCallRealModel() {
        ReActAgent agent = createAgent("""
                Follow output instructions exactly.
                Keep responses extremely short.
                """);

        Msg result = agent.call(
                        List.of(new UserMessage("Reply with the token: E2E_OK")))
                .block();

        assertNotNull(result);
        assertNotNull(result.getTextContent());
        assertTrue(result.getTextContent().contains("E2E_OK"), result::getTextContent);
    }

    @Test
    @Timeout(60)
    void shouldKeepIndependentModelRequestsAssociatedWithTheirInputs() {
        String firstToken = uniqueToken("FIRST");
        String secondToken = uniqueToken("SECOND");
        ReActAgent firstAgent = createAgent(
                "independent-model-agent-first",
                "Reply only TOKEN=<token supplied by the user>.");
        ReActAgent secondAgent = createAgent(
                "independent-model-agent-second",
                "Reply only TOKEN=<token supplied by the user>.");

        String firstReply = callForText(firstAgent, "Reply only TOKEN=" + firstToken + ".");
        String secondReply = callForText(secondAgent, "Reply only TOKEN=" + secondToken + ".");

        assertTrue(firstReply.contains(firstToken),
                () -> "First request returned the wrong token: " + firstReply);
        assertFalse(firstReply.contains(secondToken),
                () -> "First request leaked the second token: " + firstReply);
        assertTrue(secondReply.contains(secondToken),
                () -> "Second request returned the wrong token: " + secondReply);
        assertFalse(secondReply.contains(firstToken),
                () -> "Second request leaked the first token: " + secondReply);
    }

    @Test
    @Timeout(60)
    void shouldFailClearlyForUnavailableModel() {
        String missingModel = "ollama:missing-e2e-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> {
            ReActAgent agent = ReActAgent.builder()
                    .name("missing-model-e2e-agent")
                    .sysPrompt("Reply briefly.")
                    .model(missingModel)
                    .build();
            agent.call(List.of(new UserMessage("Reply OK"))).block();
        }, "an unavailable model must fail instead of returning an empty success");

        assertNotNull(error.getMessage(), "model failure must contain a diagnostic message");
        assertFalse(error.getMessage().isBlank(), "model failure message must not be blank");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModelConfigurations")
    @Timeout(10)
    void shouldReportDiagnosableFailuresForInvalidModelConfiguration(
            String description, InvalidModelConfiguration configuration) {
        RuntimeException error = assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        RuntimeException.class,
                        configuration::invoke,
                        description + " must fail instead of returning an empty success"),
                description + " must fail within the configured deadline");

        String diagnostics = diagnosticChain(error).toLowerCase(Locale.ROOT);
        assertFalse(diagnostics.isBlank(), description + " must expose a diagnostic message");
        assertTrue(
                configuration.expectedDiagnostics().stream().anyMatch(diagnostics::contains),
                () -> description + " returned an unrelated error: " + diagnostics);
    }

    private static Stream<Arguments> invalidModelConfigurations() {
        return Stream.of(
                invalidModelId("blank model id", "   ", "blank", "modelid"),
                invalidModelId("missing provider prefix", "qwen3:0.6b", "provider", "model"),
                invalidModelId("unknown provider", "unknown-e2e:model", "provider", "unknown-e2e"),
                Arguments.of(
                        Named.of("malformed base URL", "malformed base URL"),
                        new InvalidModelConfiguration(
                                () -> callWithOllamaModel(
                                        "not a valid URL", Duration.ofMillis(500)),
                                List.of("url", "uri", "scheme", "timeout"))),
                httpFailure("HTTP 401", 401, "unauthorized", "401"),
                httpFailure("HTTP 403", 403, "forbidden", "403"),
                httpFailure("HTTP 429", 429, "rate limited", "429"),
                httpFailure("HTTP 500", 500, "server error", "500"),
                httpFailure("HTTP 503", 503, "unavailable", "503"),
                serverFailure(
                        "server accepts connection but does not respond",
                        exchange -> sleepWithoutResponding(),
                        "timeout", "timed out"),
                serverFailure(
                        "malformed JSON response",
                        exchange -> respond(exchange, 200, "{not-json\n"),
                        "json", "parse", "deserialize", "null value"),
                serverFailure(
                        "incomplete protocol response",
                        exchange -> respond(exchange, 200, "{\"done\":true}\n"),
                        "message", "content", "response", "protocol"));
    }

    private static Arguments invalidModelId(
            String description, String modelId, String... expectedDiagnostics) {
        return Arguments.of(
                Named.of(description, description),
                new InvalidModelConfiguration(
                        () -> callWithModelId(modelId), Arrays.asList(expectedDiagnostics)));
    }

    private static Arguments httpFailure(
            String description, int status, String body, String... expectedDiagnostics) {
        return serverFailure(
                description,
                exchange -> respond(exchange, status, body),
                Stream.concat(Arrays.stream(expectedDiagnostics), Stream.of("timeout"))
                        .toArray(String[]::new));
    }

    private static Arguments serverFailure(
            String description, ExchangeHandler handler, String... expectedDiagnostics) {
        return Arguments.of(
                Named.of(description, description),
                new InvalidModelConfiguration(
                        () -> withServer(handler), Arrays.asList(expectedDiagnostics)));
    }

    private static void callWithModelId(String modelId) {
        ReActAgent agent = ReActAgent.builder()
                .name("invalid-model-configuration-agent")
                .sysPrompt("Reply briefly.")
                .model(modelId)
                .build();
        agent.call(List.of(new UserMessage("Reply OK"))).block();
    }

    private static void withServer(ExchangeHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                try {
                    handler.handle(exchange);
                } catch (Exception error) {
                    throw new IOException("local model stub failed", error);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            callWithOllamaModel("http://127.0.0.1:" + server.getAddress().getPort(),
                    Duration.ofMillis(500));
        } finally {
            server.stop(0);
        }
    }

    private static void callWithOllamaModel(String baseUrl, Duration timeout) {
        OllamaChatModel.Builder modelBuilder = OllamaChatModel.builder()
                .modelName("e2e-error-matrix")
                .baseUrl(baseUrl);
        if (timeout != null) {
            HttpTransportConfig config = HttpTransportConfig.builder()
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    .writeTimeout(timeout)
                    .build();
            modelBuilder.httpTransport(JdkHttpTransport.builder().config(config).build());
        }
        List<ChatResponse> responses = modelBuilder.build()
                .stream(
                        List.of(new UserMessage("Reply OK")),
                        List.of(),
                        GenerateOptions.builder().build())
                .collectList()
                .block(Duration.ofSeconds(2));
        boolean missingContent = responses == null || responses.isEmpty()
                || responses.stream().allMatch(response ->
                        response.getContent() == null || response.getContent().isEmpty());
        if (missingContent) {
            throw new IllegalStateException("protocol response is missing message content");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sleepWithoutResponding() {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String diagnosticChain(Throwable error) {
        StringBuilder diagnostics = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                diagnostics.append(current.getClass().getSimpleName())
                        .append(": ")
                        .append(current.getMessage())
                        .append('\n');
            }
        }
        return diagnostics.toString();
    }

    private record InvalidModelConfiguration(
            ThrowingInvocation invocation, List<String> expectedDiagnostics) {
        private void invoke() throws Exception {
            invocation.invoke();
        }
    }

    @FunctionalInterface
    private interface ThrowingInvocation {
        void invoke() throws Exception;
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private String callForText(ReActAgent agent, String input) {
        Msg result = agent.call(List.of(new UserMessage(input))).block();
        assertNotNull(result, "agent call must emit a result");
        String text = result.getTextContent();
        assertNotNull(text, "agent result must contain text");
        assertFalse(text.isBlank(), "agent result text must not be blank");
        return text;
    }

    private String uniqueToken(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
