package io.agentscope.e2e;

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
import java.util.List;
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
                "You must use the requested tool. Return only RESULT=<tool result>.",
                tool);

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call add_numbers. Call add_numbers with a=17 and b=25.
                        Then output RESULT=<tool result>.
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
                "Use only the tool explicitly requested by the user. "
                        + "Never call send_notification unless the user asks to send a notification. "
                        + "After add_numbers succeeds, return only RESULT=<tool result>.",
                addNumbers,
                sendNotification);

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call add_numbers with a=17 and b=25.
                        Do not call send_notification. Do not send any notification.
                        Then output RESULT=<tool result>.
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
    void shouldReportToolFailureWithoutClaimingSuccess() {
        FailingOperation failingOperation = new FailingOperation();
        ReActAgent agent = createToolAgent(
                "tool-failure-e2e-agent",
                "You must use the requested tool exactly once. "
                        + "If the tool reports an error, do not retry it and reply only TOOL_FAILED.",
                failingOperation);

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call fail_operation now.
                        If it fails, reply only TOOL_FAILED. Never claim RESULT=SUCCESS.
                        """)))
                .block();

        assertNotNull(result, "agent call must emit a result after a tool failure");
        assertEquals(1, failingOperation.invocationCount.get(),
                "a failed tool operation must not be retried implicitly");
        String text = result.getTextContent();
        assertNotNull(text, "tool-failure result must contain text");
        assertTrue(text.contains("TOOL_FAILED"), () -> "Unexpected tool-failure reply: " + text);
        assertFalse(text.contains("RESULT=SUCCESS"),
                () -> "Tool failure was reported as success: " + text);
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
}
