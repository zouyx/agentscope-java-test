package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
        ReActAgent firstAgent = createAgent("Reply only TOKEN=<token supplied by the user>.");
        ReActAgent secondAgent = createAgent("Reply only TOKEN=<token supplied by the user>.");

        String firstReply = callForText(firstAgent, "Reply only TOKEN=" + firstToken + ".");
        String secondReply = callForText(secondAgent, "Reply only TOKEN=" + secondToken + ".");

        assertTrue(firstReply.contains("TOKEN=" + firstToken),
                () -> "First request returned the wrong token: " + firstReply);
        assertFalse(firstReply.contains(secondToken),
                () -> "First request leaked the second token: " + firstReply);
        assertTrue(secondReply.contains("TOKEN=" + secondToken),
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
