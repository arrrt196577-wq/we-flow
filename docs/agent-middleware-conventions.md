# Agent 中间件开发约定

> 本文是 agent runtime 中间件体系（`WeflowMiddleware` + `MiddlewareManager` +
> `ModelRuntime` / `ToolRuntime`）的**约定集**，属于持续维护的活文档。
> 命名只是其中一节，后续与中间件相关的约定（上下文对象、失败语义、顺序规则、
> 新增阶段流程等）都追加到本文，避免约定散落在各处。
>
> 相关代码：`we-flow-workflow/src/main/java/org/example/weflow/workflow/agent/runtime/`

## 维护说明

- 新增一类约定时，在下方追加一个 `## N. 标题` 小节，保持编号递增。
- 约定一旦确立，应同步到 `WeflowMiddleware` 的类级 Javadoc（开发者实现钩子时的第一落点）。
- 与现状不一致的地方，在对应小节标注"现状/迁移"，不要让文档与代码静默背离。

---

## 1. Hook 命名语法

唯一命名规则：**`<边界><阶段>`**。

- **边界（edge）** 只允许三个词：`before` / `around` / `after`。
- **阶段（phase）** 是名词，当前为：`Run` / `Model` / `Tool` / `Finish`。
- 方法名永远从"边界 × 阶段"网格里取，**禁止再引入第四个词**（`on`、`start`、`end`、`wrap` 等一律不用）。

一句话：任何钩子都叫 `before/around/after` + 阶段名，没有例外。

### 网格

| 阶段＼边界 | `before*`（阻断式） | `around*`（环绕式） | `after*`（阻断式） |
|---|---|---|---|
| **TurnInitialization** | `beforeTurnInitialization` | `aroundTurnInitialization` | `afterTurnInitialization` |
| **Run** | `beforeRun` | `aroundRun` | `afterRun` |
| **Model** | `beforeModel` | `aroundModel` | `afterModel` |
| **Tool** | `beforeTool` | `aroundTool` | `afterTool` |
| **Finish** | `beforeFinish` | `aroundFinish` | `afterFinish` |

- `aroundTurnInitialization` returns `Map<String, Object>` through `TurnInitializationCall`
  and is currently wired by `AgentGraphFactory.initializeTurnNode`.

- 网格已全量定义于 `WeflowMiddleware` 接口 + `MiddlewareManager` 调度层。
- `around*` 钩子的续延接口与终端返回类型：`aroundRun → AgentThreadState`（`RunCall`）、
  `aroundModel → AiMessage`（`ModelCall`）、`aroundTool → Command`（`ToolCall`）、
  `aroundFinish → Map<String,Object>`（`FinishCall`）。
- 新增阶段时，沿用同一套边界词，先把网格行补出来再决定实现哪几个。

### 为什么是这套

1. **名字直接编码风格**：`before/after` 一律阻断式，`around` 一律环绕式，见名知签名与返回值。
2. **对齐业界主流**：Spring AOP `@Before/@Around/@After`、JUnit `@BeforeEach/@AfterEach`、AspectJ 同构，零学习成本。
3. **词序统一**：永远"边界在前、名词在后"，杜绝 `RunStart`（后缀）与 `beforeModel`（前缀）混用的词序翻转。
4. **消除同义词歧义**：`afterRun` 表示"整次运行的收尾"，与 `Finish`（产出/校验最终答案的节点）语义清晰分离。

### 现状/迁移

- 命名收敛已完成：`onRunStart → beforeRun`、`onRunEnd → afterRun`，全量钩子均符合本约定。
- 网格 15 个钩子已在 `WeflowMiddleware` 接口与 `MiddlewareManager` 全部建出（预留扩展面）。
- **接线现状**：实际接入调用点的仅 `aroundTurnInitialization`（`AgentGraphFactory.initializeTurnNode`）、
  `aroundModel`（`ModelRuntime`）、`aroundTool`（`ToolRuntime`）、
  `beforeFinish`（`AgentGraphFactory.finalizeNode`）。其余钩子（含 `beforeTurnInitialization` /
  `afterTurnInitialization` / 本次新增的 `aroundRun` /
  `aroundFinish` / `afterFinish` 及既有的 `beforeRun` / `afterRun` / `beforeModel` / `afterModel` /
  `beforeTool` / `afterTool`）已定义但**尚未接线**，待有真实消费方时在对应调用点接入即可。

---

## 2. 两种 Hook 风格契约

### 风格一 · 阻断式（`before*` / `after*`）

- **定位**：观察 + 决策点，不持有真实调用。
- **返回**：`MiddlewareResult`，四种结局——
  - `CONTINUE` 放行（`MiddlewareResult.continueProcessing()`）
  - `SHORT_CIRCUIT` 短路并附带 state 更新（`shortCircuit(update)`）
  - `RETRY` 打回重试并附反馈（`retry(feedback)`）
  - `FAIL` 受控失败（`fail(code, message)`）
- **适用**：校验、放行/拦截、上报指标、改写 state 等"旁观决策"。

### 风格二 · 环绕式（`around*`）

- **定位**：拦截器/洋葱，真实调用被 `next` 续延包在内部。
- **返回**：对应领域对象（`AiMessage` / `Command` 等），**不**返回 `MiddlewareResult`。
- **适用**：计时、`try/finally` 兜底、整段重试、改写请求/响应、缓存命中直接返回等"包裹调用"。

### 选择原则

- 仅需观察或拦截 → 用风格一（扁平、用统一结局词汇表达）。
- 需要把调用夹在中间、改写输入输出或兜底异常 → 用风格二。
- **流式陷阱**：`aroundModel` 包裹的是流式模型调用，若不调用 `next` 直接返回 `AiMessage`，会跳过整个 streaming（`partialSink` 收不到增量），实现短路逻辑时务必注意。

---

## 3. 返回值与执行顺序约定

| 边界 | 返回类型 | 多中间件执行顺序 |
|---|---|---|
| `before*` | `MiddlewareResult` | 注册正序，遇第一个非 `CONTINUE` 即停 |
| `around*` | 领域对象 | 洋葱序：先注册的在外层包裹后注册的 |
| `after*` | `MiddlewareResult` | 建议逆序（与洋葱展开一致）<sup>※</sup> |

<sup>※</sup> 现状：`MiddlewareManager.firstBlocking` 对 `after*` 仍是正序"第一个生效"。
若要彻底对齐洋葱语义，需将 `after*` 改为逆序遍历——属于行为变更，需单独评估后再落地。

---

## 4. 上下文对象约定

- 每个阶段配一个不可变 `record` 上下文：`ModelCallContext` / `ToolCallContext` / `FinishContext` / `AgentRunContext`。
- 构造器内做 **null 校验 + 防御性拷贝**（`List.copyOf` 等），可选字段统一归一化为 `Optional.empty()` 或空集合，不向外暴露可变引用。
- 上下文只承载该阶段所需的输入；产出对象（如 `AiMessage`、`Command`）通过 `after*` 钩子的第二参数传入，不塞回上下文。

---

## 5. 失败与重试语义约定

- 受控失败统一走 `MiddlewareResult.fail(code, message)`，由 `MiddlewareManager.failureUpdate` / `controlledFailureText` 渲染成稳定的 `status: error` 文本，**不要**在中间件里各自拼错误字符串。
- 重试统一走 `MiddlewareResult.retry(feedback)`，反馈文本会作为 `UserMessage` 回灌给模型。
- 中间件应只表达"结局意图"（继续/短路/重试/失败），由 graph 节点（如 `finalizeNode`）负责把结局翻译成 state 更新，二者职责不混。

---

<!-- 后续约定从此处继续追加：## 6. ... -->
