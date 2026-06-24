# 调研报告生成闭环与通用 Agent 演进路线图

Date: 2026-06-24

## 目标

先闭环一个**调研报告生成**能力（竞品调研报告 / 特定领域文献收集与总结分析），
在此基础上把架构逐步推向**通用 agent** 平台。

本文档基于当前代码与已有设计盘点现状，给出分阶段规划。相关文档：

- [Agent/Subagent Architecture Evolution](./agent-subagent-architecture-evolution.md)
- [Agent Runtime 横切逻辑重构 Checklist](./agent-runtime-refactor-checklist.md)
- [Next Steps: Agent/Subagent Evolution](./next-26-6-17.md)

---

## 一、现状盘点

当前已经是**仿 DeerFlow 的 deep-research 架构**，骨架较完整。

### 已有底座

| 维度 | 现状 |
|---|---|
| 运行时 | `we-flow-workflow`：LangGraph4j + LangChain4j 的 ReAct 图（`model ↔ tool` 循环） |
| 统一构建路径 | `AgentSpec` + `AgentGraphFactory` + `SubAgentRegistry` + `delegate_task` |
| 已有 agent | `lead_agent`（全工具协调）、`search_agent`（只读研究，六段式输出 + `[citation:Title](URL)`）、`implement_agent`（占位） |
| 工具 | `web_search`、`web_fetch`、`find_files / read_file / list_dir`、`delegate_task` |
| 横切能力（进行中） | 正把超时/限额/输出校验抽到 `ModelRuntime / ToolRuntime / MiddlewareManager`；新增 `SearchAgentOutputValidationMiddleware` / `CitationValidationMiddleware` |
| 持久化 | `threads_meta / runs / run_events / feedback / long_term_memories`，含 token 计费字段 |
| 接口 | `ChatController` 流式输出（`ChatStreamChunk`） |

### 距离“报告闭环”的关键缺口

1. 没有**计划层**（research plan / 大纲），目前由 lead 临时决定是否委派。
2. 没有**报告写作 agent**（reporter/writer），长报告靠 lead 顺手拼，质量不可控。
3. 没有**产物（artifact）概念与存储/导出**，报告只能作为聊天文本输出。
4. 引用仅在**单次输出内**校验，缺全局去重、统一编号、参考文献区。
5. 缺**文献类来源**（arXiv / Semantic Scholar / Scholar 等）与来源质量/去重。
6. 缺**覆盖度 / 结构校验**（章节是否写到、是否都有证据支撑）。

---

## 二、Phase A：闭环“调研报告生成”

目标：一句主题 →（计划）→ 多源研究 → 结构化长报告（带参考文献）→ 可存储 / 导出。

> A0–A5 为最小闭环；A6–A8 为质量与产品化。

### A0. 定义产物与契约（地基）

- 新增 `ResearchReport` 产物模型：`title / sections[] / references[] / metadata`
  （主题、模板类型、来源数、token 等）。
- 新增 artifact 存储：建议新表 `artifacts`；或先复用 workspace 落地 markdown 文件，二选一先跑通。
- 把 `search_agent` 输出从“自由文本六段式”升级为可机器解析的结构（先软 schema，
  后续 response_format），与正在做的输出校验 middleware 对接。

### A1. 计划层（planner）

- 新增 `plan_agent`，或在 lead 内做“生成研究大纲”步骤：
  输入主题 → 输出结构化大纲（章节 + 每章子问题 + 检索关键词）。
- 对应 DeerFlow 的 `planner` 角色，让后续研究**按章节并行**，而非漫游式 ReAct。

### A2. 强化研究执行

- `search_agent` 已是只读研究器，重点补：**多来源 + 去重 + 来源质量**。
- 抽象 `SourceConnector`：在通用 `web_search` 之外，加**文献连接器**
  （arXiv / Semantic Scholar / 可选 Scholar）；竞品侧偏官网 / 新闻 / 评测。
- 支持**按大纲章节并行委派**（已有 `delegate_task` + 子图，天然支持）。

### A3. 报告写作 agent（reporter）

- 新增 `report_agent`（写作型子 agent）：吃“大纲 + 各章节研究结果 + 证据池”，
  产出长 markdown，每个论断挂引用。
- 这是当前最大的空缺，也是“闭环”的临门一脚。`implement_agent` 占位可暂不动。

### A4. 引用与参考文献统一

- 建 run 级**证据池 / 来源表**：每条来源全局唯一 id，跨章节复用，结尾自动生成 References。
- `CitationValidationMiddleware` 从“格式校验”扩展到“引用 id 必须存在于来源池”，
  **不做 live fetch**（规避 SSRF，与重构 checklist 判断一致）。

### A5. 产物存储与导出

- 报告落库（`artifacts`）+ 关联 `thread_id / run_id`，前端可回看。
- 导出：markdown →（可选）HTML / PDF / DOCX。先 markdown 即可闭环。

### A6. 质量校验（复用 middleware 底座）

- 覆盖度校验：大纲每章都被写到；每章至少 N 条带引用证据；总长度 / 结构合规。
- 不合格走 retry 回边（checklist 中已规划 `FINALIZE → MODEL`）。

### A7.（可选）人在环

- 计划确认（plan 出来后用户改 / 批准再研究）、必要时澄清主题。
- DeerFlow 有这一步，对“竞品调研”尤其有用。

### A8. 模板化（直接对应两个目标场景）

- **竞品调研模板**：竞品清单 → 维度对比（功能 / 价格 / 定位 / 优劣）→ 对比矩阵 + 结论。
- **文献综述模板**：按主题聚类 → 方法 / 结论 / 局限 → 趋势时间线 + 严格引用。
- 模板 = 大纲生成策略 + 来源连接器组合 + 报告结构。完成后“报告生成”真正闭环且可复用。

---

## 三、Phase B：走向通用 Agent

报告闭环跑通后，架构基本是“通用 deep-research / agent 平台”的雏形，再往通用推进：

- **B1 落地 `implement_agent`**：按 [next-26-6-17.md](./next-26-6-17.md) 设计——
  只读工具 + `apply_patch / write_file` + `run_command`（allowlist：`rg / mvn test / git status` 等），
  先权限边界后开工具。
- **B2 配置外置**（evolution 文档 Phase 3）：agent 的 prompt / 工具 / 权限 / 限额从 Java 硬编码挪到配置 / DB，
  **加 agent 只改配置不改 graph**。
- **B3 记忆系统接入**：`long_term_memories` 表已就位，接成 middleware（记忆注入 / 写回），跨会话沉淀领域知识。
- **B4 工具生态**：MCP 接入、浏览器工具、代码执行沙箱，把能力从“查 + 写”扩到“执行验证”。
- **B5 planner 泛化**：把 A1 的报告 planner 抽象成通用任务规划器，支撑非报告类多步任务。
- **B6 技能 / 领域模板体系**：把 A8 的报告模板泛化成“skills”，不同领域 = 不同技能包。

---

## 四、横切关注点（贯穿各阶段）

- **先收尾 middleware 重构**：它是报告质量校验、通用化、记忆 / 权限的共同底座，半成品状态会拖累后续所有事。
- **可观测与成本**：`runs` 已有 token 字段，补 tracing 与分段计费（lead vs subagent vs reporter）。
- **评测体系**：报告质量打分、引用准确率、覆盖度做成可回归的 eval，否则通用化后无法判断是否退化。
- **安全**：已把 fetched web content 当不可信源处理（prompt injection），继续保持；artifact / 导出注意越权。

---

## 五、建议的最近三步（最小闭环优先）

1. **收尾 middleware 重构** + 跑通 `search_agent` 结构化输出校验（A0 契约部分）。
2. **加 `report_agent` + artifact 存储**，打通 `主题 → 大纲(A1) → 研究(A2) → 报告 markdown(A3/A5)` 一条直线，先不追求完美质量。
3. **上两个模板**（竞品调研 / 文献综述）验证闭环，再回头补 A4 / A6 的引用与覆盖度。

---

## 六、每日工作清单（Todo List）

> 约定：每个 “Day” 视为一个专注工作块（约半天到一天，按你的节奏拉伸 / 压缩）。
> 每天收尾固定动作：跑对应模块测试（`mvn -pl we-flow-workflow -am test`）+ 小步提交。
> 完成就勾选 `[x]`；本计划覆盖 Phase A 的最小闭环（A0–A5）+ 模板（A8）+ 质量（A6），约四周。

### 第 1 周：收尾 middleware 重构 + search_agent 输出契约（地基 / A0）

**Day 1 — 摸清基线**
- [ ] 通读 `ModelRuntime` / `ToolRuntime` / `MiddlewareManager` / `WeflowMiddleware`，对照 checklist E 节列未完成项
- [ ] 跑 `mvn -pl we-flow-workflow -am test`，确认基线全绿并记录
- [ ] 把未完成项拆成本周小任务

**Day 2 — ModelRuntime 收尾**
- [ ] 确认 `chat()` + 有效超时计算已迁入 `ModelRuntime`
- [ ] 确认流式 partial（`onPartialResponse` / `onPartialThinking`）有独立通道，未被 wrap 掩盖
- [ ] 补 / 跑 ModelRuntime 相关测试

**Day 3 — ToolRuntime + MiddlewareManager 收尾**
- [ ] `executeTools` + `invocationContext` 迁入 `ToolRuntime`，异常归一化收口
- [ ] `MiddlewareManager` 落定 onion 顺序、短路（shortCircuit）、失败文本统一格式
- [ ] 验证失败契约未变：`GraphBackedAgentExecutor.hasFailure()` 与 `TaskDelegationTool` 解析仍兼容

**Day 4 — 定义 search_agent 输出契约（A0）**
- [ ] 定下软 schema 字段（summary / findings / sources / recommended_next / gaps / citations）
- [ ] 更新 `DefaultAgentSpecs.searchAgentSpec` 的 prompt 与输出格式说明
- [ ] 在 `SearchAgentValidationSupport` 实现“什么算 valid”的解析

**Day 5 — 输出校验 + retry 回边**
- [ ] `SearchAgentOutputValidationMiddleware` 接到 `beforeFinish`，不合格 → retry
- [ ] `CitationValidationMiddleware` 做格式 + 一致性校验（**不 live fetch**）
- [ ] 接 `FINALIZE → MODEL` 的 retry 回边（带 retry 预算 state）

**Day 6 — 测试与回归**
- [ ] 加测试：search_agent 仅可见 `web_search / web_fetch / find_files / read_file / list_dir`
- [ ] 加测试：输出缺段 / 缺引用 → 触发 retry → 修复
- [ ] `mvn -pl we-flow-workflow -am test` 全绿

**Day 7 — 缓冲 / 文档**
- [ ] 处理本周遗留，回归
- [ ] 更新 checklist E 节勾选项，记录关键决策

### 第 2 周：计划层 planner（A1）

**Day 8 — 设计 plan 数据结构**
- [ ] 在 `we-flow-core` 定义 `ResearchPlan`（章节 + 子问题 + 检索关键词）
- [ ] 决定 planner 形态：独立 `plan_agent`（建议）还是 lead 内步骤，复用 `AgentGraphFactory`

**Day 9 — plan_agent spec**
- [ ] 在 `DefaultAgentSpecs` 加 `planAgentSpec`（prompt：主题 → 结构化大纲）
- [ ] 注册到 `SubAgentRegistry` / 配置

**Day 10 — plan 输出解析与校验**
- [ ] plan 输出解析成 `ResearchPlan`
- [ ] 加 plan 校验（章节非空、每章有关键词），不合格 retry

**Day 11 — lead 编排接入**
- [ ] lead prompt：复杂调研先生成 plan 再按章节委派
- [ ] plan 写入 thread state，供后续节点读取

**Day 12 — 测试**
- [ ] “给定主题 → 产出合理大纲” 的测试
- [ ] `mvn -pl we-flow-workflow -am test` 全绿

**Day 13–14 — 缓冲 / 人在环（A7 可选）**
- [ ] （可选）plan 确认 / 编辑节点
- [ ] 回归 + 文档

### 第 3 周：研究执行强化 + reporter（A2 + A3）

**Day 15 — SourceConnector 抽象**
- [ ] 抽 `SourceConnector` 接口，把 `web_search` 收进去
- [ ] 预留文献连接器位（arXiv / Semantic Scholar）

**Day 16 — 证据池（A4 前置）**
- [ ] run 级来源池：来源全局唯一 id + 去重
- [ ] search_agent 结果写入证据池

**Day 17 — 按章节并行研究**
- [ ] lead 按 plan 章节并行 `delegate_task(search_agent)`
- [ ] 各章节研究结果汇总到 state

**Day 18 — report_agent spec（A3）**
- [ ] `DefaultAgentSpecs.reportAgentSpec`：吃大纲 + 证据池 → 长 markdown
- [ ] 注册 reporter，定工具策略（只读 + 写产物）

**Day 19 — reporter 接入编排**
- [ ] 研究完成 → 委派 reporter 生成报告
- [ ] 论断挂引用 id

**Day 20–21 — 端到端 + 缓冲**
- [ ] 端到端：主题 → 大纲 → 研究 → markdown 报告
- [ ] `mvn -pl we-flow-workflow -am test`

### 第 4 周：产物存储 / 导出 + 引用 + 模板 + 质量（A5 + A4 + A8 + A6）

**Day 22 — artifacts 存储**
- [ ] 建 `artifacts` 表（或 workspace 落地），关联 `thread_id / run_id`
- [ ] 报告落库 + 读取接口

**Day 23 — 参考文献 + 引用校验（A4）**
- [ ] 结尾自动生成 References
- [ ] `CitationValidationMiddleware` 扩展为“引用 id 必须在来源池”

**Day 24 — 导出**
- [ ] markdown 导出；（可选）HTML

**Day 25 — 竞品调研模板（A8）**
- [ ] 模板 = 大纲策略 + 来源组合 + 报告结构
- [ ] 竞品维度对比矩阵

**Day 26 — 文献综述模板（A8）**
- [ ] 接入学术来源连接器
- [ ] 主题聚类 + 趋势时间线 + 严格引用

**Day 27 — 覆盖度 / 质量校验（A6）**
- [ ] 每章写到 + 每章 ≥ N 条带引用证据，不合格 retry

**Day 28 — 闭环验收**
- [ ] 两个模板各跑一个真实主题，验收报告
- [ ] `mvn clean test` 全绿，更新本文档勾选项

> 第 4 周后即完成报告生成最小闭环。Phase B（通用 agent）再按第三节单独排期，不纳入本日历。
