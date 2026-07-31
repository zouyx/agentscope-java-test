package io.agentscope.e2e;

import io.agentscope.core.agent.react.ReActAgent;

abstract class E2eTestSupport {
    protected static final String MODEL_ID =
            System.getenv().getOrDefault("E2E_MODEL_ID", "ollama:qwen3:0.6b");

    protected ReActAgent createAgent(String prompt) {
        return ReActAgent.builder()
                .name("e2e-agent")
                .sysPrompt(prompt)
                .model(MODEL_ID)
                .build();
    }
}
