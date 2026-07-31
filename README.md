# AgentScope Java user E2E

This project exercises the complete user-facing path through AgentScope's model registry and
Ollama provider using the small `qwen3:0.6b` model. It contains smoke, Java tool-calling, and
streaming integration tests. The tests are disabled during normal Maven builds and enabled by
the `e2e` profile.

## Run locally

Java 17, Maven, Docker, and roughly 1 GB of free disk space are required.

```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama
docker exec ollama ollama pull qwen3:0.6b

export OLLAMA_BASE_URL=http://localhost:11434
export E2E_MODEL_ID=ollama:qwen3:0.6b
mvn -B -pl agentscope-e2e-tests -am verify -Pe2e
```

`E2E_MODEL_ID` is optional and defaults to `ollama:qwen3:0.6b`. If tool calling proves flaky on
a particular CPU, pull `qwen3:1.7b` and set `E2E_MODEL_ID=ollama:qwen3:1.7b` without changing the
tests.

## CI diagnostics

The workflow waits for Ollama, pulls and lists the model, and performs a direct `/api/chat`
preflight before Maven starts. This separates container or model-download failures from
AgentScope integration failures. Failsafe reports and Ollama logs are retained on failures.
