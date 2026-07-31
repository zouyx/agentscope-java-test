package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.runtime.RuntimeContext;
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
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tool);
        ReActAgent agent = ReActAgent.builder()
                .name("tool-e2e-agent")
                .sysPrompt("You must use the requested tool. Return only RESULT=<tool result>.")
                .model(MODEL_ID)
                .toolkit(toolkit)
                .build();

        Msg result = agent.call(List.of(new UserMessage("""
                        You MUST call add_numbers. Call add_numbers with a=17 and b=25.
                        Then output RESULT=<tool result>.
                        """)), RuntimeContext.empty())
                .block();

        assertNotNull(result);
        assertEquals(1, tool.invocationCount.get(), "the Java tool must be invoked exactly once");
        assertTrue(result.getTextContent().contains("42"), result::getTextContent);
    }

    static final class AddNumbers {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Tool(name = "add_numbers", description = "Returns a + b.")
        public int add(@ToolParam(name = "a") int a, @ToolParam(name = "b") int b) {
            invocationCount.incrementAndGet();
            return a + b;
        }
    }
}
