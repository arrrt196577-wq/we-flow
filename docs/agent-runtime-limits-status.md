# Agent 工具调用次数限制与超时限制现状

> 记录时间：2026-06-25
> 适用范围：Agent 运行时（`we-flow-core` / `we-flow-workflow`）与 Web 工具、LLM 客户端（`we-flow-integration`）

## 总览

工具调用次数限制和超时限制集中在 Agent 运行时层，由以下三者协作，最终在
`AgentGraphFactory` 的图节点中执行：

- `AgentRuntimeLimits`（`we-flow-core`）：限制项的不可变定义。
- `AgentRuntimeProperties`（`we-flow-workflow`）：外部配置（前缀 `we-flow.agent`）。
- `DefaultAgentSpecs`（`we-flow-workflow`）：默认常量，配置缺省时回落到这里。

此外，Web 工具与 LLM 客户端各自还有一层独立于 Agent 层的 HTTP 超时。

涉及的核心文件：

- `we-flow-core/src/main/java/org/example/weflow/core/agent/AgentRuntimeLimits.java`
- `we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/AgentRuntimeProperties.java`
- `we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/DefaultAgentSpecs.java`
- `we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/AgentGraphFactory.java`
- `we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/runtime/ModelRuntime.java`
- `we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/runtime/ToolRuntime.java`
- `we-flow-integration/src/main/java/org/example/weflow/integration/search/WebSearchProperties.java`
- `we-flow-integration/src/main/java/org/example/weflow/integration/fetch/WebFetchProperties.java`
- `we-flow-integration/src/main/java/org/example/weflow/integration/llm/openai/OpenAiStreamingChatModelConfiguration.java`

---

## 一、工具调用次数限制

### 1. Lead Agent 工具调用配额（`leadToolCallLimit`）

这是真正意义上的"工具调用次数限制"，**只对 LEAD agent 生效**，subagent 不受此约束
（`AgentRuntimeLimits.lead(...)` 才会传入 `leadToolCallLimit`）。

| 项 | 默认值 | 说明 |
|---|---|---|
| `warningThreshold` | 30 | 某工具调用达到此数，给该工具结果追加警告文本 |
| `stopThreshold` | 50 | 某工具调用达到此数，硬停止本轮 |

关键特征（`AgentGraphFactory.leadToolLimitDecision`）：

- **按工具名称分别计数**，不是所有工具调用的总和。即 `web_search` 用了 30 次和
  `read_file` 用了 30 次是各自独立计数的。
- 计数存放于 `LEAD_TOOL_CALL_COUNTS` 状态，并在每轮 turn 开始时重置为空
  （`initializeTurn`），因此是"单次请求内"的配额，不跨轮累计。
- 达到 warning 阈值时，往该工具结果文本后追加一段 `LEAD_TOOL_CALL_WARNING` 提示。
- 达到 stop 阈值时返回失败码 `LEAD_TOOL_CALL_LIMIT_EXCEEDED`。

约束校验（`AgentRuntimeLimits.ToolCallLimit` 构造）：`warningThreshold` 与
`stopThreshold` 都必须 ≥ 1，且 `warningThreshold < stopThreshold`，否则构造时抛异常。

### 2. LLM 循环次数限制（`maxLoops`）

间接的"调用次数"上限——限制一次请求里 LLM 被调用的总轮数（每轮 `modelNode` 调用后
`loopCount + 1`），从而间接限制 模型 → 工具 → 模型 的循环深度。

| Agent | 默认 maxLoops | 常量 |
|---|---|---|
| Lead | 100 | `DEFAULT_LEAD_MAX_LOOPS` |
| Subagent | 50 | `DEFAULT_SUBAGENT_MAX_LOOPS` |

当 `loopCount >= maxLoops` 时返回失败码 `MAX_LOOPS_EXCEEDED`
（`AgentGraphFactory.failureBeforeModel`）。

---

## 二、超时限制

共 4 个层级，前两个在 Agent 运行时，后两个在外部 HTTP 客户端。

### 1. 单次 LLM 调用超时（`llmTimeout`，默认 600s）

- Lead 和 subagent 共用同一默认值 `DEFAULT_LLM_TIMEOUT = 600s`。
- 实现：对响应 future 调用 `orTimeout`（`ModelRuntime.callChatModel`）。
- 实际生效超时为 `min(llmTimeout, 剩余整体超时)`（`ModelRuntime.effectiveLlmTimeout`）。
- 超时分类：若是被整体 deadline 裁剪导致，归为 `SUB_AGENT_TIMEOUT`，否则归为
  `LLM_TIMEOUT`。

### 2. 子 Agent 整体超时（`overallTimeout` / `subagentTimeout`，默认 900s）

- **只有 subagent 有整体超时，Lead Agent 没有**
  （`AgentRuntimeLimits.lead(...)` 的 `overallTimeout` 为 `null`）。
- 在 `initializeTurn` 时设置 deadline：`now + overallTimeout`
  （写入 `DEADLINE_EPOCH_MILLIS`）。
- 每次 model / tool 节点执行前检查 `overallTimeoutExceeded`。
- 工具执行同样受"剩余整体时间"裁剪（`ToolRuntime.executeToolService` 中再次 `orTimeout`），
  剩余时间为零或负数时直接抛 `TimeoutException`。

### 3. Web 工具 HTTP 超时（独立于 Agent 层）

| 工具 | 客户端总超时（block） | 透传 Jina 的 `x-timeout` | 配置前缀 | 常量 |
|---|---|---|---|---|
| `web_search` | 25s | 15s | `we-flow.search.timeout` | `WebSearchProperties.DEFAULT_TIMEOUT` |
| `web_fetch` | 60s | 50s | `we-flow.fetch.timeout` | `WebFetchProperties.DEFAULT_TIMEOUT` |

- 客户端总超时通过 Reactor 的 `.block(properties.timeout())` 实现，限制从建连到下载
  响应体的全过程。
- 透传给 Jina 的 `x-timeout` 不再等于 block，而是 `clamp(block - 10s 缓冲, 1, 180)`
  （`jinaTimeoutSeconds()`）。这样服务端抓取预算始终小于客户端等待预算，留出网络往返
  与响应下载的余量，避免客户端在 Jina 还在合法抓取时就先行超时。
- 该层超时与 Agent 层整体超时**互不感知**。

### 4. LLM HTTP 客户端超时（600s）

`OpenAiStreamingChatModel` 在客户端层硬编码 `DEFAULT_STREAMING_TIMEOUT = 600s`，
与 Agent 层 `llmTimeout` 数值一致但属于不同机制，目前不可配置。

---

## 三、配置方式与现状

所有 Agent 层限制都可通过 `we-flow.agent.*` 外部配置覆盖
（`AgentRuntimeProperties`，前缀 `we-flow.agent`）：

- `we-flow.agent.max-loops.lead` / `we-flow.agent.max-loops.subagent`
- `we-flow.agent.llm-timeout`
- `we-flow.agent.subagent-timeout`
- `we-flow.agent.lead-tool-call-limit.warning-threshold` /
  `we-flow.agent.lead-tool-call-limit.stop-threshold`

配置缺省时回落到 `DefaultAgentSpecs` 常量；任何配成 ≤ 0 的值都会在启动时报错。

> **现状提示**：仓库中没有纳入版本控制的 `application.yml` / `application.yaml`
> （`src/main/resources` 下未发现配置文件，可能在本地或被 gitignore）。
> 因此除非本地另有配置，目前实际运行的都是代码默认值。

---

## 四、汇总表

| 限制类型 | 作用对象 | 默认值 | 可配置项 | 触发结果 |
|---|---|---|---|---|
| 单工具调用次数-警告 | 仅 Lead | 30 | `lead-tool-call-limit.warning-threshold` | 结果追加警告文本 |
| 单工具调用次数-停止 | 仅 Lead | 50 | `lead-tool-call-limit.stop-threshold` | `LEAD_TOOL_CALL_LIMIT_EXCEEDED` |
| LLM 循环次数 | Lead | 100 | `max-loops.lead` | `MAX_LOOPS_EXCEEDED` |
| LLM 循环次数 | Subagent | 50 | `max-loops.subagent` | `MAX_LOOPS_EXCEEDED` |
| 单次 LLM 超时 | Lead + Subagent | 600s | `llm-timeout` | `LLM_TIMEOUT` / `SUB_AGENT_TIMEOUT` |
| 整体超时 | 仅 Subagent | 900s | `subagent-timeout` | `SUB_AGENT_TIMEOUT` |
| web_search HTTP 超时 | 工具 | 25s（x-timeout 15s） | `we-flow.search.timeout` | 工具返回 error |
| web_fetch HTTP 超时 | 工具 | 60s（x-timeout 50s） | `we-flow.fetch.timeout` | 工具返回 error |
| LLM 客户端超时 | LLM 调用 | 600s | 暂硬编码 | 流式中断 |

---

## 五、需要注意的点

1. **Lead Agent 没有整体墙钟超时**，只靠 `maxLoops(100)` + 单次 LLM 超时 + 单工具配额
   三道闸兜底，理论上极端情况下可能跑很久。
2. **工具配额按工具名分别计数**而非总量，若需控制总调用量需额外逻辑。
3. **Web 工具超时与 Agent 整体超时不联动**，subagent 临近 deadline 时仍可能发起一个
   最长 60s（web_fetch）的网络请求。
