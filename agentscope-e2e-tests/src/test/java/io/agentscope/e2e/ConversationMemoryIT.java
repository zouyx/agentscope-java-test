package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConversationMemoryIT extends E2eTestSupport {
    private static final String MEMORY_PROMPT = """
            You are testing conversation memory. Follow these rules exactly:
            - Remember project codes supplied by the user during this conversation.
            - When asked for the current project code, reply only CODE=<current code>.
            - If no project code was supplied in this conversation, reply only UNKNOWN.
            - A newer project code replaces an older project code.
            """;

    @Test
    @Timeout(60)
    void shouldRecallFactFromPreviousTurn() {
        String code = uniqueCode("ORBIT");
        ReActAgent agent = createMemoryAgent("recall-agent");

        assertText(agent, "Remember that my project code is " + code + ".");
        String recalled = assertText(agent, "What is my current project code?");

        assertTrue(recalled.contains("CODE=" + code), () -> "Unexpected recall: " + recalled);
        assertFalse(recalled.contains("UNKNOWN"), () -> "Agent forgot the code: " + recalled);
    }

    @Test
    @Timeout(60)
    void shouldIsolateMemoryBetweenAgents() {
        String code = uniqueCode("ORBIT");
        ReActAgent agentA = createMemoryAgent("isolation-agent-a");
        ReActAgent agentB = createMemoryAgent("isolation-agent-b");

        assertText(agentA, "Remember that my project code is " + code + ".");
        String agentBReply = assertText(agentB, "What is my current project code?");
        String agentAReply = assertText(agentA, "What is my current project code?");

        assertTrue(agentBReply.contains("UNKNOWN"),
                () -> "Independent agent unexpectedly knew a code: " + agentBReply);
        assertFalse(agentBReply.contains(code),
                () -> "Project code leaked to an independent agent: " + agentBReply);
        assertTrue(agentAReply.contains("CODE=" + code),
                () -> "Original agent did not retain its code: " + agentAReply);
    }

    @Test
    @Timeout(60)
    void shouldForgetFactAfterConversationReset() {
        String code = uniqueCode("ORBIT");
        ReActAgent originalAgent = createMemoryAgent("reset-agent-before");

        assertText(originalAgent, "Remember that my project code is " + code + ".");
        String beforeReset = assertText(originalAgent, "What is my current project code?");
        assertTrue(beforeReset.contains("CODE=" + code),
                () -> "Precondition failed; code was not remembered: " + beforeReset);

        // A new Agent with a new Memory represents a new conversation. This verifies the public
        // user path without inspecting or mutating Memory internals.
        ReActAgent resetAgent = createMemoryAgent("reset-agent-after");
        String afterReset = assertText(resetAgent, "What is my current project code?");

        assertTrue(afterReset.contains("UNKNOWN"),
                () -> "New conversation unexpectedly retained a code: " + afterReset);
        assertFalse(afterReset.contains(code),
                () -> "Old project code leaked into the new conversation: " + afterReset);
    }

    @Test
    @Timeout(60)
    void shouldUseLatestCorrectionInConversationOrder() {
        String oldCode = uniqueCode("ORBIT");
        String newCode = uniqueCode("NOVA");
        ReActAgent agent = createMemoryAgent("ordering-agent");

        assertText(agent, "Remember that my project code is " + oldCode + ".");
        assertText(agent, "The previous code is obsolete. My new project code is "
                + newCode + ".");
        String recalled = assertText(agent, "What is my current project code?");

        assertTrue(recalled.contains("CODE=" + newCode),
                () -> "Agent did not use the latest correction: " + recalled);
        assertFalse(recalled.contains(oldCode),
                () -> "Agent returned the obsolete project code: " + recalled);
    }

    private ReActAgent createMemoryAgent(String name) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(MEMORY_PROMPT)
                .model(MODEL_ID)
                .build();
    }

    private String assertText(ReActAgent agent, String input) {
        Msg result = agent.call(List.of(new UserMessage(input))).block();
        assertNotNull(result, "agent call must emit a result");
        String text = result.getTextContent();
        assertNotNull(text, "agent result must contain text");
        assertFalse(text.isBlank(), "agent result text must not be blank");
        return text;
    }

    private String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
