package io.agentscope.e2e.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.e2e.support.E2eTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ConversationMemoryIT extends E2eTestSupport {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final String MEMORY_PROMPT = """
            You are testing conversation memory. Follow these rules exactly:
            - Remember project codes supplied by the user during this conversation.
            - When asked for the current project code, reply only CODE=<current code>.
            - If no project code was supplied in this conversation, reply only UNKNOWN.
            - A newer project code replaces an older project code.
            """;
    private static final String CONCURRENT_MEMORY_PROMPT = """
            You are testing concurrent conversation memory. Follow these rules exactly:
            - Remember project codes supplied by the user during this conversation.
            - When supplied a project code, reply only SAVED=<supplied code>.
            - When asked for the current project code, reply only CODE=<current code>.
            - If no project code was supplied in this conversation, reply only UNKNOWN.
            """;

    @Test
    @Timeout(60)
    void shouldRecallFactFromPreviousTurn() {
        String code = uniqueCode("ORBIT");
        ReActAgent agent = createMemoryAgent("recall-agent");

        assertText(agent, "Remember that my project code is " + code + ".");
        String recalled = assertText(agent, "What is my current project code?");

        assertTrue(recalled.contains(code), () -> "Unexpected recall: " + recalled);
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
        assertTrue(agentAReply.contains(code),
                () -> "Original agent did not retain its code: " + agentAReply);
    }

    @Test
    @Timeout(150)
    void shouldIsolateConcurrentCallsAcrossAgents() throws Exception {
        int agentCount = 2;
        List<String> codes = new ArrayList<>();
        List<ReActAgent> agents = new ArrayList<>();
        for (int index = 0; index < agentCount; index++) {
            codes.add(uniqueCode("CONCURRENT"));
            agents.add(createConcurrentMemoryAgent("concurrent-isolation-agent-" + index));
        }

        List<Callable<ConversationReplies>> calls = new ArrayList<>();
        for (int index = 0; index < agentCount; index++) {
            ReActAgent agent = agents.get(index);
            String code = codes.get(index);
            calls.add(() -> {
                String writeReply = assertTextWithin(agent,
                        "Remember that my project code is " + code + ".");
                String readReply = assertTextWithin(agent, "What is my current project code?");
                return new ConversationReplies(writeReply, readReply);
            });
        }

        List<ConversationReplies> replies = runConcurrently(calls, Duration.ofSeconds(120));
        for (int index = 0; index < agentCount; index++) {
            String ownCode = codes.get(index);
            ConversationReplies agentReplies = replies.get(index);
            assertEquals("SAVED=" + ownCode, agentReplies.writeReply().trim(),
                    () -> "Unexpected concurrent write reply: " + agentReplies.writeReply());
            assertEquals("CODE=" + ownCode, agentReplies.readReply().trim(),
                    () -> "Unexpected concurrent read reply: " + agentReplies.readReply());
            for (String otherCode : codes) {
                if (!otherCode.equals(ownCode)) {
                    assertFalse(agentReplies.writeReply().contains(otherCode),
                            () -> "Concurrent write reply leaked another code: "
                                    + agentReplies.writeReply());
                    assertFalse(agentReplies.readReply().contains(otherCode),
                            () -> "Concurrent read reply leaked another code: "
                                    + agentReplies.readReply());
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void shouldForgetFactAfterConversationReset() {
        String code = uniqueCode("ORBIT");
        ReActAgent originalAgent = createMemoryAgent("reset-agent-before");

        assertText(originalAgent, "Remember that my project code is " + code + ".");
        String beforeReset = assertText(originalAgent, "What is my current project code?");
        assertTrue(beforeReset.contains(code),
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

        assertTrue(recalled.contains(newCode),
                () -> "Agent did not use the latest correction: " + recalled);
        assertFalse(recalled.contains(oldCode),
                () -> "Agent returned the obsolete project code: " + recalled);
    }

    @Test
    @Timeout(60)
    void shouldRetainFactAcrossUnrelatedConversationTurn() {
        String code = uniqueCode("ORBIT");
        ReActAgent agent = createMemoryAgent("intervening-turn-agent");

        assertText(agent, "Remember that my project code is " + code + ".");
        String unrelatedReply = assertText(agent, "Reply only with the result of 7 plus 5.");
        assertTrue(unrelatedReply.contains("12"),
                () -> "Intervening turn did not complete as requested: " + unrelatedReply);

        String recalled = assertText(agent, "What is my current project code?");

        assertTrue(recalled.contains(code),
                () -> "Agent lost the code after an unrelated turn: " + recalled);
        assertFalse(recalled.contains("UNKNOWN"),
                () -> "Agent forgot the code after an unrelated turn: " + recalled);
    }

    private ReActAgent createMemoryAgent(String name) {
        return createMemoryAgent(name, MEMORY_PROMPT);
    }

    private ReActAgent createConcurrentMemoryAgent(String name) {
        return createMemoryAgent(name, CONCURRENT_MEMORY_PROMPT);
    }

    private ReActAgent createMemoryAgent(String name, String prompt) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(MODEL_ID)
                .build();
    }

    private String assertText(ReActAgent agent, String input) {
        Msg result = agent.call(List.of(new UserMessage(input))).block();
        return assertText(result);
    }

    private String assertTextWithin(ReActAgent agent, String input) {
        Msg result = agent.call(List.of(new UserMessage(input))).block(CALL_TIMEOUT);
        return assertText(result);
    }

    private String assertText(Msg result) {
        assertNotNull(result, "agent call must emit a result");
        String text = result.getTextContent();
        assertNotNull(text, "agent result must contain text");
        assertFalse(text.isBlank(), "agent result text must not be blank");
        return text;
    }

    private String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private <T> List<T> runConcurrently(List<? extends Callable<T>> calls, Duration timeout)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        List<Future<T>> futures = List.of();
        try {
            futures = executor.invokeAll(calls, timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                assertFalse(future.isCancelled(), "concurrent agent call timed out");
                results.add(future.get());
            }
            return results;
        } finally {
            for (Future<T> future : futures) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "concurrent agent tasks did not terminate");
        }
    }

    record ConversationReplies(String writeReply, String readReply) {
    }
}
