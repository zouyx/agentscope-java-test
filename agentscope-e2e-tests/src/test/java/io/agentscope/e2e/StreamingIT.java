package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StreamingIT extends E2eTestSupport {
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
        ReActAgent agent = createAgent("Reply only STREAM_OK=<token supplied by the user>.");
        List<String> textEvents = new CopyOnWriteArrayList<>();

        agent.call(List.of(new UserMessage("Reply only STREAM_OK=" + token + ".")))
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
}
