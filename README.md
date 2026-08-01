# AgentScope Java User-Focused E2E Tests

This repository validates key AgentScope Java capabilities from an **SDK user's perspective**.
The tests use only public APIs and a real Ollama model to verify user-visible outcomes instead
of inspecting framework internals.

## Test coverage

| Scenario | Test class | Test case design | User-visible acceptance criteria |
| --- | --- | --- | --- |
| Basic model call | `ModelSmokeIT` | [`model-call-testcases.md`](testcase/model-call-testcases.md) | The Agent accepts a request and returns the requested content |
| Streaming output | `StreamingIT` | [`streaming-output-testcases.md`](testcase/streaming-output-testcases.md) | The call emits at least one non-empty text message |
| Java tool calling | `ToolCallingIT` | [`java-tool-calling-testcases.md`](testcase/java-tool-calling-testcases.md) | The Agent calls the tool exactly once and returns the calculated result |
| Multi-turn memory | `ConversationMemoryIT` | [`conversation-memory-testcases.md`](testcase/conversation-memory-testcases.md) | The Agent remembers, isolates, resets, and updates conversation data in order |

See [`agent.md`](agent.md) for test principles, scenario design, and the checklist for adding
test cases. See
[`testcase/README.md`](testcase/README.md) for the complete user-path test case index and details.

## Project structure

```text
.
|-- agentscope-e2e-tests/     # JUnit 5 end-to-end test module
|-- testcase/                 # Detailed test case designs
|-- agent.md                  # User-focused guide for testers
`-- pom.xml                   # Maven aggregator and AgentScope version configuration
```

## Prerequisites

- Java 17
- Maven 3.8 or later
- Docker
- Approximately 1 GB of free disk space
- Local port `11434` available for Ollama

## Quick start

The following commands start the same model service used by the tests and run the complete suite:

```bash
docker run -d --name ollama -p 11434:11434 ollama/ollama
docker exec ollama ollama pull qwen3:0.6b
OLLAMA_BASE_URL=http://localhost:11434 \
  mvn -B -pl agentscope-e2e-tests -am verify -Pe2e
```

The first run can take longer while Docker and Maven download their dependencies. A successful run
ends with `BUILD SUCCESS`; detailed integration-test results are written to
`agentscope-e2e-tests/target/failsafe-reports/`.

## Run locally

1. Start Ollama and download the default small model:

   ```bash
   docker run -d --name ollama -p 11434:11434 ollama/ollama
   docker exec ollama ollama pull qwen3:0.6b
   ```

2. Configure the model service and run all E2E tests:

   ```bash
   export OLLAMA_BASE_URL=http://localhost:11434
   export E2E_MODEL_ID=ollama:qwen3:0.6b
   mvn -B -pl agentscope-e2e-tests -am verify -Pe2e
   ```

`E2E_MODEL_ID` is optional and defaults to `ollama:qwen3:0.6b`. If tool calling is unstable on
a particular CPU, pull `qwen3:1.7b` and set `E2E_MODEL_ID=ollama:qwen3:1.7b` without changing the
tests.

### Configuration reference

| Setting | Default | Purpose |
| --- | --- | --- |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Base URL of the Ollama HTTP service |
| `E2E_MODEL_ID` | `ollama:qwen3:0.6b` | AgentScope model identifier used by every scenario |
| Maven profile `e2e` | Disabled | Enables the `*IT` integration tests during `verify` |

Keep the `ollama:` prefix in `E2E_MODEL_ID`; the value after it must match a model shown by
`docker exec ollama ollama list`. CI intentionally uses `ollama:qwen3:1.7b` for more reliable tool
calling, while the smaller default keeps local setup lightweight.

### Run a single scenario

```bash
mvn -B -pl agentscope-e2e-tests -am verify -Pe2e \
  -Dit.test=ConversationMemoryIT
```

Replace `ConversationMemoryIT` with another test class from the coverage table to run that
scenario. Normal Maven builds skip these integration tests by default. The `e2e` profile enables
tests whose class names end in `IT`.

## Interpret test results

- A Maven exit code of `0` with no failures or errors in the Failsafe report means the tests
  passed.
- On failure, first confirm that Ollama is reachable and the selected model has been downloaded.
  Then use the assertion message to distinguish a missing response, an output protocol mismatch,
  a tool-calling failure, forgotten conversation data, or cross-session leakage.
- Do not determine success from natural-language phrasing alone. The tests verify fixed protocol
  markers and unique tokens that represent the required behavior.

### Troubleshooting

| Symptom | Check | Resolution |
| --- | --- | --- |
| Connection refused on port `11434` | `curl -fsS "$OLLAMA_BASE_URL/api/tags"` | Start Ollama or correct `OLLAMA_BASE_URL` |
| Model not found | `docker exec ollama ollama list` | Pull the model named by `E2E_MODEL_ID` |
| No integration tests run | Maven output says the Failsafe tests are skipped | Add `-Pe2e` and run the `verify` phase |
| Tool scenario varies between runs | Rerun only `ToolCallingIT` and record the model ID | Use `qwen3:1.7b` and confirm the model is fully downloaded |
| A scenario times out | Call `/api/chat` directly and inspect `docker logs ollama` | Separate a slow or unhealthy model service from an SDK failure |

To stop and remove the local service after testing, run `docker rm -f ollama`.

## CI diagnostics

The CI workflow waits for Ollama, pulls and lists the model, and calls `/api/chat` directly as a
preflight check before Maven starts. This separates container and model-download failures from
AgentScope integration failures. Failsafe reports and Ollama logs are retained when a test fails.
