package io.agentscope.e2e.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.e2e.support.E2eTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolCallingIT extends E2eTestSupport {
    @Test
    @Timeout(60)
    void shouldExecuteJavaTool() {
        AddNumbers tool = new AddNumbers();
        ReActAgent agent = createToolAgent(
                "tool-e2e-agent",
                "You must use the requested tool. After it succeeds, reply only with the actual "
                        + "numeric result using RESULT=<number>. Never copy placeholder text.",
                tool);

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call add_numbers. Call add_numbers with a=17 and b=25.
                        Then output the actual numeric result. Do not copy placeholder text.
                        """)))
                .block();

        assertNotNull(result);
        assertEquals(1, tool.invocationCount.get(), "the Java tool must be invoked exactly once");
        assertEquals(17, tool.lastA, "the Java tool must receive argument a");
        assertEquals(25, tool.lastB, "the Java tool must receive argument b");
        assertTrue(result.getTextContent().contains("42"), result::getTextContent);
    }

    @Test
    @Timeout(60)
    void shouldSelectRequestedToolWithoutCallingUnrelatedTool() {
        AddNumbers addNumbers = new AddNumbers();
        SendNotification sendNotification = new SendNotification();
        ReActAgent agent = createToolAgent(
                "tool-selection-e2e-agent",
                "You must use the requested tool. After it succeeds, reply only with the actual "
                        + "numeric result using RESULT=<number>. Never copy placeholder text.",
                addNumbers,
                sendNotification);

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call add_numbers. Call add_numbers with a=17 and b=25.
                        Then output the actual numeric result. Do not copy placeholder text.
                        """)))
                .block();

        assertNotNull(result, "agent call must emit a result");
        assertEquals(1, addNumbers.invocationCount.get(), "add_numbers must be invoked exactly once");
        assertEquals(17, addNumbers.lastA, "add_numbers must receive argument a");
        assertEquals(25, addNumbers.lastB, "add_numbers must receive argument b");
        assertEquals(0, sendNotification.invocationCount.get(),
                "the unrelated notification tool must not be invoked");
        assertTrue(result.getTextContent().contains("42"), result::getTextContent);
    }

    @Test
    @Timeout(60)
    void shouldKeepConcurrentToolArgumentsAndResultsIsolated() throws Exception {
        String firstToken = uniqueToken();
        String secondToken = uniqueToken();
        ConcurrentEchoTool firstTool = new ConcurrentEchoTool();
        ConcurrentEchoTool secondTool = new ConcurrentEchoTool();
        ReActAgent firstAgent = createToolAgent(
                "concurrent-tool-agent-a",
                concurrentToolPrompt(),
                firstTool);
        ReActAgent secondAgent = createToolAgent(
                "concurrent-tool-agent-b",
                concurrentToolPrompt(),
                secondTool);

        List<String> replies = runConcurrently(List.of(
                () -> callConcurrentTool(firstAgent, firstToken),
                () -> callConcurrentTool(secondAgent, secondToken)), Duration.ofSeconds(50));

        assertToolInvocation(firstTool, firstToken);
        assertToolInvocation(secondTool, secondToken);
        assertEquals("RESULT=" + firstToken, normalizeProtocolReply(replies.get(0)),
                () -> "Unexpected first concurrent result: " + replies.get(0));
        assertFalse(replies.get(0).contains(secondToken),
                () -> "First concurrent result leaked the other token: " + replies.get(0));
        assertEquals("RESULT=" + secondToken, normalizeProtocolReply(replies.get(1)),
                () -> "Unexpected second concurrent result: " + replies.get(1));
        assertFalse(replies.get(1).contains(firstToken),
                () -> "Second concurrent result leaked the other token: " + replies.get(1));
    }

    @Test
    @Timeout(60)
    void shouldNotRetryFailedSideEffectingToolByDefault() {
        FailingOperation failingOperation = new FailingOperation();
        ReActAgent agent = createToolAgent(
                "tool-failure-e2e-agent",
                "You must use the requested tool before replying. If the tool reports an error, "
                        + "do not retry it and reply only TOOL_FAILED.",
                failingOperation);

        Msg result = null;
        RuntimeException failure = null;
        try {
            result = agent.call(List.of(new UserMessage("""
                            You MUST call fail_operation now.
                            """)))
                    .block();
        } catch (RuntimeException error) {
            failure = error;
        }

        assertEquals(1, failingOperation.invocationCount.get(),
                "a failed side-effecting tool must not be retried by default");
        if (failure != null) {
            assertTrue(hasMessageInCauseChain(failure, "controlled tool failure"),
                    "Unexpected propagated tool failure: " + failure);
            return;
        }

        assertNotNull(result, "agent call must emit a result after a tool failure");
        String text = result.getTextContent();
        assertNotNull(text, "tool-failure result must contain text");
        String normalizedText = text.toLowerCase(Locale.ROOT);
        assertTrue(normalizedText.contains("fail") || normalizedText.contains("error"),
                () -> "Tool failure was not reported: " + text);
        assertFalse(text.contains("RESULT=SUCCESS"),
                () -> "Tool failure was reported as success: " + text);
    }

    @Test
    @Timeout(120)
    void shouldBindComplexJavaToolArguments() {
        String note = "发布 \"北极星\" release " + uniqueToken();
        ComplexArgumentsTool tool = new ComplexArgumentsTool();
        ReActAgent agent = createToolAgent(
                "complex-tool-arguments-e2e-agent",
                "You must call format_release exactly once with every value supplied by the "
                        + "user. Preserve strings character for character. After it succeeds, "
                        + "reply only with the exact value returned by the tool.",
                tool);

        Msg result = agent.call(List.of(new UserMessage("""
                        Call format_release exactly once with note=%s, urgent=true,
                        channel=CANARY, and retry_limit=-1. Return its exact result.
                        """.formatted(note))))
                .block();

        assertNotNull(result, "complex-argument tool call must emit a result");
        assertEquals(1, tool.invocationCount.get(), "format_release must be invoked exactly once");
        String singleQuotedNote = note.replace('"', '\'');
        assertTrue(tool.lastNote.equals(note) || tool.lastNote.equals(singleQuotedNote),
                () -> "Unicode, spaces, and quotes must be preserved: " + tool.lastNote);
        assertTrue(tool.lastUrgent, "boolean argument must be bound as true");
        assertEquals(ReleaseChannel.CANARY, tool.lastChannel, "enum argument must be bound");
        assertEquals(-1, tool.lastRetryLimit, "negative numeric argument must be bound");
        String text = result.getTextContent();
        assertNotNull(text, "complex-argument result must contain text");
        assertFalse(text.isBlank(), "complex-argument result text must not be blank");
        assertEquals(tool.lastResult, normalizeProtocolReply(text),
                () -> "Agent did not return the tool's business result: " + text);
    }

    @Test
    @Timeout(120)
    void shouldUseFirstToolResultAsSecondToolArgument() {
        String seed = uniqueToken();
        ChainedTools tools = new ChainedTools();
        ReActAgent agent = createToolAgent(
                "tool-chain-e2e-agent",
                "Complete the requested two-step workflow. Call create_code exactly once, then "
                        + "call confirm_code exactly once using the exact value returned by "
                        + "create_code. Reply only with the exact confirm_code result.",
                tools);

        Msg result = agent.call(List.of(new UserMessage(
                        "First call create_code with seed=\"" + seed + "\". Then pass its exact "
                                + "return value to confirm_code as code.")))
                .block();

        assertNotNull(result, "tool-chain call must emit a result");
        String generatedCode = "CODE-" + seed;
        List<String> invocationList = List.copyOf(tools.invocations);
        assertEquals(2, invocationList.size(), "tool chain must invoke exactly two tool calls");
        assertEquals("create:" + seed, invocationList.get(0), "create_code must run first");
        assertTrue(invocationList.get(1).startsWith("confirm:"),
                () -> "confirm_code must run second: " + invocationList);
        String confirmArgument = invocationList.get(1).substring("confirm:".length());
        String jsonSeed = "{\"code\":\"" + seed + "\"}";
        String jsonGeneratedCode = "{\"code\":\"" + generatedCode + "\"}";
        assertTrue(confirmArgument.equals(generatedCode)
                        || confirmArgument.equals(seed)
                        || confirmArgument.equals(jsonGeneratedCode)
                        || confirmArgument.equals(jsonSeed),
                () -> "confirm_code did not receive an expected chained value: " + invocationList);
        assertEquals(1, tools.createCount.get(), "create_code must run exactly once");
        assertEquals(1, tools.confirmCount.get(), "confirm_code must run exactly once");
        String text = result.getTextContent();
        assertNotNull(text, "tool-chain result must contain text");
        assertFalse(text.isBlank(), "tool-chain result text must not be blank");
        String normalizedReply = normalizeProtocolReply(text).replace("\\\"", "\"");
        assertTrue(normalizedReply.equals("CONFIRMED=" + confirmArgument)
                        || normalizedReply.equals("CONFIRMED=" + generatedCode)
                        || normalizedReply.equals("CONFIRMED=" + jsonGeneratedCode),
                () -> "Unexpected tool-chain result: " + text);
    }

    @Test
    @Timeout(60)
    void shouldNotInvokeRegisteredToolForUnknownToolRequest() {
        AddNumbers addNumbers = new AddNumbers();
        SendNotification sendNotification = new SendNotification();
        ReActAgent agent = createToolAgent(
                "unknown-tool-e2e-agent",
                "Only invoke a tool when its exact name is registered and explicitly requested. "
                        + "If the requested tool is unavailable, reply only TOOL_UNAVAILABLE. "
                        + "Never substitute a different tool.",
                addNumbers,
                sendNotification);

        Msg result = agent.call(List.of(new UserMessage("""
                        Call delete_everything now. This is the only operation I request.
                        Do not substitute add_numbers or send_notification.
                        """)))
                .block();

        assertNotNull(result, "agent call must emit a result");
        assertEquals(0, addNumbers.invocationCount.get(),
                "an unknown tool request must not invoke add_numbers");
        assertEquals(0, sendNotification.invocationCount.get(),
                "an unknown tool request must not invoke send_notification");
        String text = result.getTextContent();
        assertNotNull(text, "unknown-tool result must contain text");
        assertTrue(text.contains("TOOL_UNAVAILABLE"), () -> "Unexpected unknown-tool reply: " + text);
    }

    private ReActAgent createToolAgent(String name, String sysPrompt, Object... tools) {
        Toolkit toolkit = new Toolkit();
        for (Object tool : tools) {
            toolkit.registerTool(tool);
        }
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(MODEL_ID)
                .toolkit(toolkit)
                .build();
    }

    private boolean hasMessageInCauseChain(Throwable error, String expectedText) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
                return true;
            }
        }
        return false;
    }

    private String concurrentToolPrompt() {
        return "You must call echo_token exactly once with the token requested by the user. "
                + "After it succeeds, reply only with the exact value returned by the tool. "
                + "Do not add, remove, or change any characters. Do not surround the value "
                + "with quotation marks or a code fence.";
    }

    private String callConcurrentTool(ReActAgent agent, String token) {
        Msg result = agent.call(List.of(new UserMessage(
                "You MUST call echo_token exactly once with token=\"" + token + "\". "
                        + "Return its exact result."))).block();
        assertNotNull(result, "concurrent tool call must emit a result");
        String text = result.getTextContent();
        assertNotNull(text, "concurrent tool result must contain text");
        assertFalse(text.isBlank(), "concurrent tool result text must not be blank");
        return text;
    }

    private void assertToolInvocation(ConcurrentEchoTool tool, String expectedToken) {
        assertEquals(1, tool.invocationCount.get(), "the concurrent tool must be invoked exactly once");
        assertEquals(List.of(new ToolInvocation(expectedToken, "RESULT=" + expectedToken)),
                List.copyOf(tool.invocations),
                "the concurrent tool must record only its own argument and result");
    }

    private String uniqueToken() {
        return "TOKEN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private String normalizeProtocolReply(String reply) {
        String trimmed = reply.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private <T> List<T> runConcurrently(List<? extends Callable<T>> calls, Duration timeout)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        List<Future<T>> futures = List.of();
        try {
            futures = executor.invokeAll(calls, timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                assertFalse(future.isCancelled(), "concurrent tool call timed out");
                results.add(future.get());
            }
            return results;
        } finally {
            for (Future<T> future : futures) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "concurrent tool tasks did not terminate");
        }
    }

    static final class AddNumbers {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private int lastA;
        private int lastB;

        @Tool(name = "add_numbers", description = "Returns a + b.")
        public int add(@ToolParam(name = "a") int a, @ToolParam(name = "b") int b) {
            invocationCount.incrementAndGet();
            lastA = a;
            lastB = b;
            return a + b;
        }
    }

    static final class SendNotification {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Tool(name = "send_notification", description = "Records a notification without sending it.")
        public String send(@ToolParam(name = "message") String message) {
            invocationCount.incrementAndGet();
            return "NOTIFICATION_RECORDED=" + message;
        }
    }

    static final class FailingOperation {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Tool(name = "fail_operation", description = "Always fails with a controlled business error.")
        public String fail() {
            invocationCount.incrementAndGet();
            throw new IllegalStateException("controlled tool failure");
        }
    }

    enum ReleaseChannel {
        CANARY,
        STABLE
    }

    static final class ComplexArgumentsTool {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private String lastNote;
        private boolean lastUrgent;
        private ReleaseChannel lastChannel;
        private int lastRetryLimit;
        private String lastResult;

        @Tool(name = "format_release", description = "Formats all supplied release fields.")
        public String format(
                @ToolParam(name = "note") String note,
                @ToolParam(name = "urgent") boolean urgent,
                @ToolParam(name = "channel") ReleaseChannel channel,
                @ToolParam(name = "retry_limit") int retryLimit) {
            invocationCount.incrementAndGet();
            lastNote = note;
            lastUrgent = urgent;
            lastChannel = channel;
            lastRetryLimit = retryLimit;
            lastResult = "FORMATTED=" + note + "|" + urgent + "|" + channel + "|" + retryLimit;
            return lastResult;
        }
    }

    static final class ChainedTools {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger confirmCount = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> invocations = new ConcurrentLinkedQueue<>();

        @Tool(name = "create_code", description = "Creates a code from the supplied seed.")
        public String create(@ToolParam(name = "seed") String seed) {
            createCount.incrementAndGet();
            invocations.add("create:" + seed);
            return "CODE-" + seed;
        }

        @Tool(name = "confirm_code", description = "Confirms a code created by create_code.")
        public String confirm(@ToolParam(name = "code") String code) {
            confirmCount.incrementAndGet();
            invocations.add("confirm:" + code);
            return "CONFIRMED=" + code;
        }
    }

    static final class ConcurrentEchoTool {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final ConcurrentLinkedQueue<ToolInvocation> invocations = new ConcurrentLinkedQueue<>();

        @Tool(name = "echo_token", description = "Returns the supplied token as RESULT=<token>.")
        public String echo(@ToolParam(name = "token") String token) {
            String result = "RESULT=" + token;
            invocations.add(new ToolInvocation(token, result));
            invocationCount.incrementAndGet();
            return result;
        }
    }

    record ToolInvocation(String token, String result) {
    }
}
