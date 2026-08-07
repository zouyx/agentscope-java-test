# AgentScope Java User-Focused Testing Guide

## 1. Purpose

This guide explains how to design, review, and execute AgentScope Java end-to-end tests. Testers
should treat the system as a product consumed through its public Java APIs and answer these
questions:

1. Can a user complete a real Agent call by following the documented setup?
2. Do user inputs, conversation context, and tools produce correct, observable results?
3. Is data isolated between different users or conversations?
4. Do failures help users distinguish environment, model, and SDK behavior problems?

Acceptance criteria must not depend on internal classes, private fields, or implementation
algorithms. Critical user journeys must not replace the real model with a mock.

This file is the contributor policy for the E2E suite. `README.md` is the operator-facing quick
start, while documents under `testcase/` are the source of truth for individual scenario steps
and acceptance criteria. Update all affected documents in the same change whenever behavior,
configuration, or coverage changes.

## 2. User roles and critical journeys

| User role | Primary need | What to verify |
| --- | --- | --- |
| First-time SDK user | Call a model with minimal configuration | Defaults, actionable errors, and a non-empty response |
| Agent developer | Combine prompts, memory, and Java tools | Parameter handling, tool execution, and context continuity |
| Application maintainer | Operate reliably and diagnose failures quickly | Timeouts, logs, isolation, and repeatability |

Prioritize the critical user journeys as follows:

- **P0: Basic call.** Create an Agent, send a message, and receive usable text.
- **P0: Tool calling.** Select the correct tool, pass correct arguments, execute it the expected
  number of times, and return its result to the user.
- **P0: Conversation memory and isolation.** Remember information within one conversation without
  exposing it to another conversation.
- **P1: Streaming response.** Emit usable message output before the call completes.
- **P1: Conversation reset and update.** Exclude old data from a new conversation and prefer the
  latest correction over an obsolete value.

## 3. Test design principles

### 3.1 Exercise only public user paths

- Use the public AgentScope Java APIs for Agents, messages, tools, and model configuration.
- Determine outcomes from returned messages, tool side effects, and public exceptions.
- Do not inspect internal state through reflection, depend on private implementation classes, or
  treat an internal call sequence as a product contract.

### 3.2 Verify behavior, not exact natural-language prose

Model output can vary legitimately. Prompts should request a short, fixed output protocol such as
`RESULT=42`, `CODE=<token>`, or `UNKNOWN`. Assertions should check required markers, unique tokens,
and prohibited values rather than compare an entire response character for character.

### 3.3 Keep test data isolated

- Create a separate Agent and conversation for each test; do not share mutable static state.
- Generate high-entropy conversation secrets on every run so that a model cannot guess them from
  general knowledge.
- An isolation test must verify both that the target conversation does not know the secret and
  that the original conversation still does. This prevents a service outage from being mistaken
  for successful isolation.

### 3.4 Make failures diagnosable

- Give every real model call an explicit timeout.
- Check the returned object, text content, and business protocol separately, and include the
  actual response in assertion messages.
- Separate environment preflight checks from behavioral assertions: verify service health and
  model availability before running the SDK path.
- Cleanup failures must not hide the original failure, and logs must not expose credentials or
  real user data.

### 3.5 Organize tests by user scenario

Keep E2E test packages aligned with the user-facing scenario, not with AgentScope implementation
modules. Under `agentscope-e2e-tests/src/test/java/io/agentscope/e2e/`, use this layout:

| Package | Scope |
| --- | --- |
| `model` | Basic model calls, request independence, and model-configuration failures |
| `memory` | Multi-turn recall, isolation, reset, and conversation ordering |
| `streaming` | Reactive text delivery and streaming-service failure behavior |
| `tool` | Java tool selection, arguments, side effects, and tool-error handling |
| `support` | Shared public-API setup only, including model selection and common test helpers |

- Each `*IT` class belongs to exactly one scenario package.
- Scenario packages must not import test classes or helpers from one another. Promote genuinely
  reusable public-API setup to `support` instead.
- Keep scenario-specific fixtures, such as Java tools used only by tool-calling cases, beside the
  owning test class.
- When adding a scenario, create a matching package and document its test cases under `testcase/`.

## 4. Test case template

Before implementing a new JUnit 5 test class whose name ends in `IT`, document the scenario under
`testcase/` with this template:

```markdown
### TC-<MODULE>-<NUMBER>: <User behavior>

- Priority: P0 / P1 / P2
- User goal: What the user needs to accomplish
- Preconditions: Required services, model, configuration, and test data
- Steps: Public API actions that a user can perform
- Expected result: User-observable outcomes suitable for automated assertions
- Failure diagnosis: Whether a failure is likely related to the environment, model, or SDK
- Cleanup: Containers, conversations, or temporary data to remove
```

Follow these implementation conventions:

- Name test methods after behavior, for example `shouldRecallFactFromPreviousTurn`.
- Use JUnit 5 `@Test` and give real-model tests `@Timeout(60)` or an equivalent explicit timeout.
  A scenario that intentionally permits model-driven tool retries may use a longer, documented
  method-level timeout while remaining bounded.
- Reuse the model-selection logic in `E2eTestSupport` so users can switch models with
  `E2E_MODEL_ID`.
- On a positive path, verify at least a non-empty response and the business result. On a negative
  path, also verify that errors are not silently swallowed.

### 4.1 Change workflow

Use this sequence when adding or changing coverage:

1. Identify the user risk, priority, and observable success condition.
2. Add or update the scenario design under `testcase/`, including failure diagnosis and cleanup.
3. Implement the `*IT` test using only public APIs and shared configuration from
   `E2eTestSupport`.
4. Run the single scenario first, then the complete E2E profile to detect shared-state or ordering
   problems.
5. Update the coverage table and configuration guidance in `README.md` if the supported user path
   or required setup changed.

Reviews should reject a test that passes only because the model call was skipped, that asserts
exact prose instead of behavior, or that leaves its Agent, memory, or tool state available to
another test.

## 5. Execution checklist

### Before execution

- [ ] Java 17, Maven, and Docker are available.
- [ ] The Ollama service is reachable through `OLLAMA_BASE_URL`.
- [ ] The model selected by `E2E_MODEL_ID` is downloaded and responds to a direct request.
- [ ] Test data contains no real user information, credentials, or other sensitive values.
- [ ] The test creates an independent Agent and memory and does not depend on test execution order.
- [ ] The documented scenario and its automated assertions describe the same user-visible result.

### During execution

- [ ] Each request completes within its configured timeout.
- [ ] The returned message and text are non-null and non-empty.
- [ ] Fixed protocol markers and dynamic test tokens match the expected values.
- [ ] Tool invocation count, arguments, and side effects match the user's request.
- [ ] Isolation scenarios verify both no leakage and continued access in the original session.

### After execution

- [ ] Maven and Failsafe results agree with the test assertions.
- [ ] Failure logs contain the actual output without exposing sensitive data.
- [ ] A failed case is rerun to distinguish a stable defect from model variation, with the model
  name and version recorded.
- [ ] Containers, conversations, and temporary resources created by the test are removed.
- [ ] Coverage and setup changes are reflected in `README.md` and the `testcase/` index.

## 6. Defect reporting

Describe user-visible behavior in the defect title, such as "A new conversation can read the
previous conversation's project code," rather than guessing an internal root cause. Include:

- AgentScope, Java, and Maven versions and the operating system;
- Ollama version, model ID, and model digest when available;
- the minimal reproduction command and public API steps;
- expected and actual results and the reproduction rate; and
- the Failsafe report and sanitized Ollama logs.

## 7. Current coverage and next steps

The current automation covers basic and independent model calls, unavailable model and service
errors, streaming collection, Java tool selection and exceptions, concurrent session and tool
isolation, and the retention, isolation, reset, and ordering of conversation memory. Future
user-risk-based coverage can include malformed model identifiers, request cancellation, rate
limiting, and oversized contexts. Prioritize journeys that are both frequent and costly when they fail.
