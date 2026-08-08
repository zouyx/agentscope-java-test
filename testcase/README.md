# AgentScope Java 用户路径测试用例索引

## 范围

本目录针对本仓库以 AgentScope Java `2.0.0` 和本地 Ollama 为运行环境的用户可见能力
设计端到端测试用例。用例只通过公开 Java API、返回消息、流式发布信号和工具副作用验证结果，
不读取框架内部状态。

| 用户模块 | 测试用例文档 | 自动化测试类 | 优先级 |
| --- | --- | --- | --- |
| 基础模型调用、错误配置与韧性 | [model-call-testcases.md](model-call-testcases.md) | `ModelSmokeIT`、`ModelResilienceIT` | P0 / P1 / P2 |
| 流式输出 | [streaming-output-testcases.md](streaming-output-testcases.md) | `StreamingIT` | P1 |
| Java 工具调用 | [java-tool-calling-testcases.md](java-tool-calling-testcases.md) | `ToolCallingIT` | P0 |
| 多轮会话记忆 | [conversation-memory-testcases.md](conversation-memory-testcases.md) | `ConversationMemoryIT` | P0 / P1 |
| 补充方案 | [additional-testcases.md](additional-testcases.md) | `ModelSmokeIT`、`ConversationMemoryIT`、`ToolCallingIT` | P0 / P2（其余待实施） |

当前文档与自动化的对应关系为 20 个用例：基础模型调用、错误配置与韧性 6 个、流式输出 3 个、Java 工具调用
5 个、多轮会话记忆 6 个。修改 `*IT` 中的测试方法时，应同步检查对应设计文档和本索引中的
数量及优先级。

## 共用环境

- Java 17、Maven 3.8+、Docker；Ollama 服务监听 `OLLAMA_BASE_URL`（默认
  `http://localhost:11434`）。
- 默认模型为 `ollama:qwen3:0.6b`，可通过 `E2E_MODEL_ID` 覆盖；模型须已下载且能通过
  Ollama 直接响应。
- 使用 `mvn -B -pl agentscope-e2e-tests -am verify -Pe2e` 运行自动化用例。每个真实模型
  调用必须设置最多 60 秒的超时。
- 测试数据不得使用真实用户数据、密钥或业务敏感信息；每个用例独立创建 Agent、会话和工具
  实例，避免依赖执行顺序。

## 判定规则

- 以非空文本、固定协议标记、工具调用计数/参数和流式事件等可观察结果断言；不比较整段自然
  语言回复。
- 若失败，先执行 Ollama 健康检查及模型列表检查，再根据断言区分环境连接、模型遵循指令和
  AgentScope 公共 API 行为问题。
- 当前 Failsafe 配置和 CI 工作流按测试类运行，因此启用 `e2e` profile 时 P0、P1、P2 用例会
  一并执行，不支持按优先级自动筛选。优先级用于风险排序和失败处置，不代表默认跳过策略。
