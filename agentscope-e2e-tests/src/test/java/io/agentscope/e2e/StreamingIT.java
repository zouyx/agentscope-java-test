package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.react.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
}
