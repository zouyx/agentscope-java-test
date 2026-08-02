package io.agentscope.e2e.support;

import io.agentscope.core.ReActAgent;

public abstract class E2eTestSupport {
    protected static final String MODEL_ID =
            System.getenv().getOrDefault("E2E_MODEL_ID", "ollama:qwen3:0.6b");

    protected ReActAgent createAgent(String prompt) {
        return createAgent("e2e-agent", prompt);
    }

    protected ReActAgent createAgent(String name, String prompt) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(prompt)
                .model(MODEL_ID)
                .build();
    }
}
