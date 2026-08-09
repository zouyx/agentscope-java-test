package io.agentscope.e2e.streaming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.e2e.support.E2eTestSupport;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
    @Timeout(90)
    void shouldStopEmittingAfterSubscriptionCancellation() {
        ReActAgent agent = createAgent("Reply with the requested marker 50 times, separated by spaces.");
        String marker = "CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AtomicBoolean upstreamCancelled = new AtomicBoolean();

        List<AgentEvent> firstEvents = agent.streamEvents(
                        List.of(new UserMessage("Reply with " + marker + " exactly 50 times.")))
                .doOnCancel(() -> upstreamCancelled.set(true))
                .filter(ModelCallStartEvent.class::isInstance)
                .take(1)
                .collectList()
                .block(CALL_TIMEOUT);

        assertNotNull(firstEvents, "cancellation test must observe model execution starting");
        assertEquals(1, firstEvents.size(),
                "subscriber must receive exactly one model-start event before cancellation");
        assertTrue(upstreamCancelled.get(), "taking one event must cancel the upstream stream");

        Msg followUp = agent.call(List.of(new UserMessage("Reply only FOLLOWUP_OK."))).block(CALL_TIMEOUT);
        assertNotNull(followUp, "agent must remain usable after a streaming subscription is cancelled");
        assertTrue(followUp.getTextContent().contains("FOLLOWUP_OK"), followUp::getTextContent);
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
}
