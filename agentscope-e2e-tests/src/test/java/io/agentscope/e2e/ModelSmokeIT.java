package io.agentscope.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import java.util.List;
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
}
