# AgentScope E2E 补充测试方案

## 目标与范围

在现有基础模型调用、流式输出、Java 工具调用和多轮记忆的 15 个端到端用例之上，补齐并发隔离、取消、真实流式语义、工具边界、错误协议及容量风险。测试应始终通过 AgentScope 的公开配置和调用 API 触发；对 HTTP 协议层错误可使用本地可控服务模拟，避免依赖外部服务的不稳定行为。

执行约束：除已明确配置重试的场景外，每次真实模型调用的上限为 60 秒；所有并发测试都必须设置整体超时，且使用随机 token 防止测试互相污染。

## P0：优先实施

### 并发会话隔离

- 建议位置：`ConversationMemoryIT`
- 建议名称：`shouldIsolateConcurrentCallsAcrossAgents`
- 前置条件：创建多个独立 Agent，每个 Agent 使用不同的随机 token。
- 步骤：并发向各 Agent 发起写入及读取请求；等待全部请求在整体超时内完成。
- 断言：每个响应只包含自身 token；不包含任何其他 Agent 的 token；没有超时、异常或遗留任务。
- 风险覆盖：并发下请求关联错误、共享缓冲区和会话上下文串扰。

### 并发工具调用与参数隔离

- 建议位置：`ToolCallingIT`
- 建议名称：`shouldKeepConcurrentToolArgumentsAndResultsIsolated`
- 前置条件：两个 Agent 或 Toolkit 并发调用同名工具；工具以线程安全记录保存调用参数、结果和调用次数。
- 步骤：以不同随机参数同时发起请求。
- 断言：每个请求只得到自己的结果；每个参数仅被对应调用使用；各工具调用恰好一次；没有跨请求参数、结果或计数混入。
- 风险覆盖：工具参数绑定、结果缓存或调用状态在线程间串线。

## P1：高价值行为与安全边界

### 取消正在执行的流式请求

- 建议位置：`StreamingIT`
- 建议名称：`shouldStopEmittingAfterSubscriptionCancellation`
- 步骤：发起可产生较长回复的流式请求；收到首个可观察事件后取消订阅；随后发起一个新的短请求。
- 断言：取消后消费者不再收到事件；原请求及时释放且不会运行至全局超时；后续请求仍能成功，证明 Agent/HTTP 客户端未失效。

### 验证完成前的增量流式输出

- 建议位置：`StreamingIT`
- 建议名称：`shouldEmitContentBeforeOverallCompletion`
- 步骤：请求足够长且可预测的输出；记录首次文本事件和完成事件的时间及事件内容。
- 断言：完成前至少有一个可用文本事件；事件内容非空；事件顺序正确。
- 注意：先确认 AgentScope 2.0.0 的公开契约是否承诺 token/chunk 级事件。若仅承诺最终 `Msg`，不得以 chunk 数量作为硬性断言。

### 真实会话重置 API

- 建议位置：`ConversationMemoryIT`
- 建议名称：`shouldForgetContextAfterPublicReset`
- 前置条件：仅在公共 API 提供 `reset`、`clear` 或等价会话切换能力时实施。
- 步骤：在同一 Agent 写入秘密值；调用公开重置 API；再次询问；再写入一个新值并读取。
- 断言：重置后返回 `UNKNOWN` 或公开契约规定的等价结果；旧值未泄漏；Agent 仍可保存和读取新值。
- 备注：若没有公开重置 API，现有“新建 Agent”场景应继续命名为新会话隔离，不能视为 reset 覆盖。

### 复杂与非法工具参数

- 建议位置：`ToolCallingIT`
- 建议名称：
  - `shouldBindComplexJavaToolArguments`
  - `shouldRejectInvalidToolArgumentsWithoutExecutingSideEffect`
- 覆盖输入：包含空格、Unicode 和引号的字符串；boolean；enum；可选/缺失参数；负数和数字边界值；以及公共 API 明确支持时的数组或复杂对象。
- 非法参数步骤：对有副作用工具传入缺失或类型错误的参数。
- 断言：合法参数原样绑定；非法参数返回明确失败；有副作用工具完全不执行；错误不能被描述为成功。

### 多步工具链

- 建议位置：`ToolCallingIT`
- 建议名称：`shouldUseFirstToolResultAsSecondToolArgument`
- 步骤：工具 A 返回一个动态值；工具 B 必须以该值为参数；记录调用顺序和实际参数。
- 断言：调用顺序为 A 后 B；各调用次数符合策略；工具 B 收到工具 A 的真实返回值；最终文本包含第二步的业务结果。

### 工具失败不得隐式重复执行

- 建议位置：`ToolCallingIT`
- 建议名称：`shouldNotRetryFailedSideEffectingToolByDefault`
- 步骤：调用一个必然失败、且会记录副作用次数的工具。
- 断言：默认策略下调用次数恰好为 1；最终结果清晰表明失败。
- 扩展：若 SDK 提供重试配置，分别验证禁止重试与有限次数重试，且重试次数有界并在文档中写明。

## P2：协议、韧性与容量

### 错误配置矩阵

- 建议位置：`ModelSmokeIT`，可新增本地模拟服务的辅助类。
- 建议名称：`shouldReportDiagnosableFailuresForInvalidModelConfiguration`
- 覆盖场景：空模型 ID、缺失 provider 前缀、未知 provider、格式错误的 base URL、401/403、429、500/503、连接成功但不响应、malformed JSON 和不完整协议响应。
- 断言：在限定时间内失败；异常消息或 cause chain 能表明模型、HTTP 状态、连接或协议诊断；不能以空成功结果结束。

### 限流、短暂故障和重试

- 建议位置：`ModelSmokeIT` 或独立 `ModelResilienceIT`
- 建议名称：
  - `shouldRespectRetryAfterForRateLimitedModelRequest`
  - `shouldBoundRetriesForTransientModelFailure`
- 模拟场景：429 携带 `Retry-After`、503、连接中途断开、首次失败后第二次成功。
- 断言：重试行为符合公开契约；调用次数有上限；最终错误可诊断；无重试策略时快速失败。

### 超长上下文与裁剪

- 建议位置：`ConversationMemoryIT`
- 建议名称：`shouldHandleOversizedConversationContextPredictably`
- 步骤：连续写入超过模型上下文窗口的多轮消息，保留可识别的早期和近期事实。
- 断言：调用按公开策略成功裁剪或返回可诊断错误；不允许永久等待或空成功；若发生裁剪，最近关键事实的保留行为符合公开约定。

## 现有用例的断言强化

1. 基础模型失败：除“异常消息非空”外，验证消息或 cause chain 含模型、HTTP、连接或服务诊断，避免任意无关异常误通过。
2. 流式错误：显式记录 `onError`、已发布事件数量和完成状态，区分订阅、映射与服务端错误。
3. 工具成功：先断言文本非空，再检查内容，避免产生无诊断价值的空指针异常。
4. 工具结果：不要只检查包含 `42`；使用规范化的完整业务协议，例如 `RESULT=42`，并保留调用次数和实际参数断言。
5. 超时一致性：将工具失败场景的 120 秒限制对齐为 60 秒；若为重试保留 120 秒，必须注明重试次数和依据。

## 推荐实施顺序

1. 并发会话隔离。
2. 并发工具调用与参数隔离。
3. 请求取消。
4. 工具失败不重复执行。
5. 复杂及非法工具参数。
6. 完成前的流式事件。
7. 真实 reset API（仅公共 API 支持时）。
8. HTTP 错误、限流和重试。
9. 超长上下文。
10. 多步工具链。

该顺序优先处理潜在的数据泄漏、重复副作用和资源泄漏，再扩展协议边界与容量场景。
