# Agent Runtime 横切逻辑重构 Checklist

## 背景

`we-flow` 当前 agent runtime 主要在 `we-flow-workflow` 模块，通过 LangGraph4j +
LangChain4j 运行 graph-backed subagent。随着 `search_agent` 输出校验等需求增加，
继续在 graph 里加 node/分支会导致"每加功能都改 graph"的问题。

目标方向：

- **Graph**：只负责稳定的状态机编排（model ↔ tool 循环、路由）。
- **ModelRuntime / ToolRuntime**：封装模型与工具调用，触发 middleware。
- **MiddlewareManager + WeflowMiddleware**：承载业务语义扩展（超时、限额、输出校验等）。

本文档基于当前代码逐条盘点横切逻辑，并映射到规划的语义切面，作为重构前的搬迁地图。

### 关键文件

| 文件 | 职责 |
|---|---|
| `we-flow-workflow/.../AgentGraphFactory.java` | Graph 构建与 node 实现（横切逻辑集中地） |
| `we-flow-workflow/.../AgentThreadState.java` | Graph 状态 schema |
| `we-flow-workflow/.../GraphBackedAgentExecutor.java` | invoke 边界、终态 → `AgentResult` |
| `we-flow-workflow/.../DefaultAgentSpecs.java` | Agent 声明（含 search_agent 输出格式 prompt） |
| `we-flow-agent/.../TaskDelegationTool.java` | lead → subagent 委派与结果包装 |

### 当前 Graph 结构

```text
START
  -> TURN_INITIALIZATION_NODE
  -> MODEL_NODE
  -> TOOL_NODE / MODEL_NODE loop
  -> END
```

期望收敛为：

```text
START
  -> MODEL_NODE
  -> TOOL_NODE / MODEL_NODE loop
  -> FINALIZE_NODE
  -> END
```

其中 `FINALIZE_NODE` 最终可退化为 `beforeFinish` hook runner；但**输出校验 +
retry** 是控制流，可能需要一条 `FINALIZE → MODEL` 的条件回边，不要强行追求零 graph 改动。

---

## 语义切面约定

### Middleware 生命周期

| 切面 | 触发时机 | 典型用途 |
|---|---|---|
| `onRunStart` | 每次 run/turn 开始，仅一次 | 状态初始化、任务消息装配 |
| `beforeModel` | 每次模型调用前 | 守卫检查、请求装配（system prompt、tool 过滤） |
| `aroundModel` | 包裹模型调用 | 超时、重试、缓存、限流 |
| `afterModel` | 每次模型调用后 | 状态写回、unsupported tool 拦截 |
| `beforeTool` | 每次工具调用前 | 守卫检查、限额、请求装配 |
| `aroundTool` | 包裹工具调用 | 超时、权限、审计 |
| `afterTool` | 每次工具调用后 | 结果归一化、warning 追加 |
| `beforeFinish` / `validateOutput` | 即将结束、无 tool call 的最终输出 | 输出校验、修复重试 |
| `onRunEnd` / `onFailure` | run 结束 | 终态归一化、tracing |

### Middleware 返回结果

| 结果 | 含义 |
|---|---|
| `continue` | 放行，进入 next |
| `shortCircuit` | 不调 next，直接产出终态输出（finish） |
| `fail` | 归一化失败（写 `failureCode` / `failureMessage`） |
| `retry` | 带反馈重走（主要给模型层 / finalize） |
| `appendMessage` | 向 messages 追加或改写消息 |
| `replaceRequest` | 改写即将发出的请求（`ChatRequest` / tool 入参） |

> 建议先只实现真正会用到的 result 变体，其余按需补充（YAGNI）。

### 框架 Hook 与 WeflowMiddleware 的分工

| 层级 | 用途 |
|---|---|
| LangGraph4j `NodeHook` | 底层观测：node 耗时、输入输出摘要、异常日志、debug snapshot |
| WeflowMiddleware | 业务语义：输出校验、tool 权限、超时、限额、请求改写 |

不要把 `search_agent` 输出校验、tool 权限等业务逻辑放进 LangGraph4j NodeHook。

---

## A. 应迁移为 Middleware 的横切逻辑

| 横切逻辑 | 当前位置 | 归属语义切面 | 建议 Middleware | Result | 关键备注 |
|---|---|---|---|---|---|
| Turn 初始化（重置 `loopCount` / `failure` / `counts`、设 `deadline`、注入首条 `UserMessage`） | `AgentGraphFactory.initializeTurn` | **onRunStart**（每 run 一次） | `RunInitializer`（或由 Executor 在 invoke 前构造 state） | — | 切勿放 `beforeModel`（会每轮重置）。注意 `MemorySaver` checkpoint resume：lead 复用 `threadId` 时必须**覆盖**旧值，落地后实测多轮 |
| 任务消息拼装（`parentAgentCode` / `traceId` / `taskId` / `objective` / `input`） | `GraphBackedAgentExecutor.taskMessage` | **onRunStart**（输入装配） | `RunInitializer` | — | 属于 run 边界，建议留在 Executor / `onRunStart` |
| 已有 failure 时短路（model 前） | `failureBeforeModel` | **beforeModel** 守卫 | `FailureGuard` | `shortCircuit` / `fail` | 多个守卫共用，建议统一一个短路守卫 |
| overall deadline 超时检查（model 前） | `failureBeforeModel` | **beforeModel** 守卫 | `TimeoutMiddleware` | `fail`(`SUB_AGENT_TIMEOUT`) | 与 `aroundModel` 的超时同源，建议同一 middleware 承载 |
| max loops 检查 | `failureBeforeModel` | **beforeModel** 守卫 | `LoopBudgetMiddleware` | `fail`(`MAX_LOOPS_EXCEEDED`) | `loopCount` 自增在 `afterModel`，配对管理 |
| system prompt 注入 | `messagesForModel` | **beforeModel**（请求装配） | `SystemPromptMiddleware` | `replaceRequest` | 也可留作 ModelRuntime 核心装配，二选一即可 |
| 按 `toolPolicy` 过滤可见工具 | `availableToolSpecifications` | **beforeModel**（请求装配） | `ToolPolicyMiddleware` | `replaceRequest` | 与 unsupported tool 拦截是同一策略的两面 |
| 有效 LLM 超时计算（`min(llmTimeout, remaining)`） | `effectiveLlmTimeout` / `timeoutUsesOverallDeadline` | **aroundModel** | `TimeoutMiddleware` | 控制 next 调用 | wrap 才能算"剩余时间"并裁剪本次调用 |
| LLM 调用超时执行 + 分类（LLM vs SUB_AGENT） | `chat()` `orTimeout` | **aroundModel** | `TimeoutMiddleware` | `fail` | `CompletionException → TimeoutException` 的 unwrap 收口到 Runtime |
| 持久化 assistant 消息 + `loopCount+1` | `modelNode` 返回 | **afterModel**（状态写回） | （ModelRuntime 核心翻译） | `appendMessage` | `loopCount` 自增是 bookkeeping，归 `afterModel` |
| unsupported tool 拦截 + 本地化提示 | `routeAfterModel` + `unsupportedTool*` | **afterModel** / **beforeTool** 守卫 | `UnsupportedToolMiddleware` | `shortCircuit` | 直接 finish 并替换输出；中文提示文案随迁 |
| 已有 failure 时短路（tool 前） | `failureBeforeTool` | **beforeTool** 守卫 | `FailureGuard` | `shortCircuit` / `fail` | 同上的 `FailureGuard` 复用 |
| overall deadline 超时检查（tool 前） | `failureBeforeTool` | **beforeTool** 守卫 | `TimeoutMiddleware` | `fail` | 同上 |
| lead tool 调用限额（warning / stop 阈值、计数） | `leadToolLimitDecision` | **beforeTool** | `LeadToolCallLimitMiddleware` | `fail`(stop) / `continue` + 状态更新 | 仅 `LEAD` 且配置了 limit 时生效 |
| tool 入参 / `InvocationContext` 装配 | `invocationContext` | **beforeTool** / **aroundTool**（装配） | （ToolRuntime 核心） | `replaceRequest` | `chatMemoryId` 取自 `thread_id` |
| tool 执行超时 + 分类 + 执行后再查超时 | `executeTools` | **aroundTool** | `TimeoutMiddleware` | `fail`(`SUB_AGENT_TIMEOUT`) | 与模型层超时同一 middleware |
| 给 tool result 追加 warning 文本 | `appendLeadToolWarnings` | **afterTool** | `LeadToolCallLimitMiddleware`（after 段） | `appendMessage` | 与 `beforeTool` 计数同属一个 middleware 的两段 |
| 持久化 tool results + counts | `toolNode` 返回 | **afterTool**（状态写回） | （ToolRuntime 核心翻译） | `appendMessage` | `MESSAGES_STATE` 是 appender reducer，注意写"增量" |
| web 工具失败归一化 + 本地化提示 | `routeAfterTool` + `webToolFailure*` | **afterTool** 守卫 | `WebToolFailureMiddleware` | `shortCircuit` | 检测最近 `web_search` / `web_fetch` 的 `status: error` 后 finish |
| **search_agent 输出校验**（目前仅 prompt 约束） | `DefaultAgentSpecs.searchAgentSpec`（prompt） | **beforeFinish** / `validateOutput` | `SearchAgentOutputValidationMiddleware` + `CitationValidationMiddleware` | `continue` / `retry` / `fail` | 触发本次重构的真实需求；retry 是控制流，需配一条 finalize → model 回边或 ModelRuntime 内 loop。citation 只做格式校验，别 live fetch |

---

## B. 应留在 Graph 的逻辑（核心状态机）

| 逻辑 | 当前位置 | 说明 |
|---|---|---|
| model 后路由：有 tool calls → TOOL，否则 → FINISH | `routeAfterModel` | 纯状态流转，graph 的本职 |
| tool 后路由：默认 CONTINUE 回 model | `routeAfterTool` | 同上 |
| 流式累积成 `AiMessage`、thinking 兜底 | `chat()` / `withThinkingFallback` | ModelRuntime 的 next 终端实现；注意给 partial 流留独立通道，别被 wrap 永久掩盖 |

---

## C. 由 Runtime / MiddlewareManager 收口的公共机制

| 机制 | 当前位置 | 收口到 |
|---|---|---|
| 失败文本统一格式（`status: error\ncode:\nmessage:`） | `failureUpdate` / `controlledFailureText` | `onFailure` 归一化；所有 `fail` / `shortCircuit` 复用同一格式（下游 `hasFailure()`、`TaskDelegationTool` 依赖它） |
| finish 短路时写 output + 追加 `AiMessage` | `finishCommand` | `shortCircuit` 机制，由 Manager / Runtime 负责 |
| 强类型 result → `Map` 增量更新的翻译 | 各 node 的 `Map<String,Object>` 返回 | ModelRuntime / ToolRuntime 集中翻译，禁止 middleware 直接碰原始 state Map |
| 异常归一化（`CompletionException` unwrap、`isTimeout`） | `isTimeout` | Runtime 统一 unwrap，middleware 只看归一化异常 |
| 终态 → `AgentResult`（success / failed、traceId、timing） | `GraphBackedAgentExecutor.execute` | **onRunEnd** / **onFailure** 边界，留在 Executor |

> `TaskDelegationTool` 把 `AgentResult.output()` 拍平成文本的逻辑属于"委派边界"，在
> agent-runtime middleware 体系之外。但若输出校验要做到端到端可机器校验，需考虑
> `AgentResult.output()` 承载结构化数据，这点会反推到这里。

---

## D. 设计注意事项

### 1. 输出校验 + retry 是控制流

`beforeFinish` 适合做校验，但 **retry 需要回到 model**，不能只在 hook 里同步重调模型
（会绕开 graph 状态机，重复 timeout / loopCount / message append 等关注点）。

可选方案：

- 保留一条 `FINALIZE → MODEL` 条件边（带 retry 预算 state）；或
- 在 ModelRuntime / finalize 步骤内做"调用 → 校验 → 不合格则带反馈重调"的 loop。

### 2. Streaming 不要被 wrap 掩盖

当前 `chat()` 把流式 token 累积成一个 `AiMessage` 返回。`wrapModelCall` 若只返回最终
`AiMessage`，将来要做前端实时输出就没有口子。ModelRuntime 接口设计时应把 partial 流
（`onPartialResponse` / `onPartialThinking`）作为独立通道暴露。

### 3. Turn 初始化与 checkpoint

`initializeTurn` 放在 graph node 里，很可能是因为 lead_agent 多轮复用同一 `threadId`
时需要每轮强制重置 `loopCount` / `failure` / `leadToolCallCounts` / `deadline`。
搬到 `onRunStart` 后，要确认这些 key 确实**覆盖** checkpoint 里的旧值。

### 4. 先定义"什么算 valid"

`search_agent` 当前是自由文本输出（prompt 约束六段式 + `[citation:Title](URL)`）。
需先决定：

- **软检查**：有没有那几段、有没有 citation → 重述修复；
- **硬 schema**：结构化输出 / response format → 对 retry 通道要求不同。

`CitationValidationMiddleware` 限定在格式 / 一致性校验，不要做 live fetch（SSRF 风险）。

---

## E. 重构推进 Checklist

- [ ] 1. 定义最小接口：`WeflowMiddleware` + 强类型 context（`AgentRunContext` / `ModelCallContext` / `ToolCallContext` / `FinishContext`）+ result 类型。先只实现真正会用到的 result 变体。
- [ ] 2. 抽 `ModelRuntime`：把 `chat()` + 有效超时计算迁入；确认流式 partial 有独立通道。
- [ ] 3. 抽 `ToolRuntime`：把 `executeTools` + `invocationContext` 迁入；异常归一化收口。
- [ ] 4. 引入 `MiddlewareManager`：定 onion 顺序、短路、异常归一化、failure 文本统一。
- [ ] 5. **垂直验证**：只接 ModelRuntime + 一个 finish 钩子，跑通 search_agent 输出校验（含 retry 回边的取舍）。
- [ ] 6. 把 `onRunStart` 接上：迁 `initializeTurn` + `taskMessage`，**实测 lead 多轮 checkpoint resume** 不串状态。
- [ ] 7. 迁 `TimeoutMiddleware`（model + tool 两处超时合一）。
- [ ] 8. 迁 `LoopBudgetMiddleware` / `FailureGuard` 守卫。
- [ ] 9. 迁 `ToolPolicyMiddleware` + `UnsupportedToolMiddleware`（同一策略两面）。
- [ ] 10. 迁 `LeadToolCallLimitMiddleware`（before 计数 + after 追加 warning）。
- [ ] 11. 迁 `WebToolFailureMiddleware` 归一化。
- [ ] 12. 迁 logging / tracing 等纯观测项（可搭 LangGraph4j NodeHook 做 node 耗时 / 快照，不放业务逻辑）。
- [ ] 13. 校验失败契约未变：`GraphBackedAgentExecutor.hasFailure()` 与 `TaskDelegationTool` 解析仍兼容。

---

## F. 建议的 Middleware 清单（目标态）

| Middleware | 职责 |
|---|---|
| `RunInitializer` | `onRunStart`：状态初始化、任务消息装配 |
| `FailureGuard` | `beforeModel` / `beforeTool`：已有 failure 短路 |
| `TimeoutMiddleware` | `beforeModel` / `beforeTool` 守卫 + `aroundModel` / `aroundTool`：超时计算与执行 |
| `LoopBudgetMiddleware` | `beforeModel`：max loops 检查 |
| `SystemPromptMiddleware` | `beforeModel`：system prompt 注入 |
| `ToolPolicyMiddleware` | `beforeModel`：按 policy 过滤可见工具 |
| `UnsupportedToolMiddleware` | `afterModel`：unsupported tool 拦截 |
| `LeadToolCallLimitMiddleware` | `beforeTool` 计数 + `afterTool` warning 追加 |
| `WebToolFailureMiddleware` | `afterTool`：web 工具失败归一化 |
| `SearchAgentOutputValidationMiddleware` | `beforeFinish`：search_agent 输出结构校验 |
| `CitationValidationMiddleware` | `beforeFinish`：citation 格式校验（不做 live fetch） |
| `ModelRequestLoggingMiddleware` | 纯观测：模型请求 / 响应日志 |

---

## 相关文档

- [Agent/Subagent Architecture Evolution](./agent-subagent-architecture-evolution.md)
