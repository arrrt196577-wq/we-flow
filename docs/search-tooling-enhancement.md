# Search Agent 检索能力增强方案

Date: 2026-06-24

## 背景

当前 `search_agent` 的对外检索能力仅有 `web_search` + `web_fetch` 两个工具。
单论功能（**本文不讨论输出格式 / 引用等约束**），对于一个定位为
「深度研究 subagent」的角色而言，这套工具更像是「够用的最小原语」，
而非「强大的研究工具集」。本文盘点现状、定位真正的瓶颈，并给出分层增强方向。

相关文档：

- [调研报告生成闭环与通用 Agent 演进路线图](./report-generation-roadmap.md)
- [Agent/Subagent Architecture Evolution](./agent-subagent-architecture-evolution.md)
- [Agent Runtime 横切逻辑重构 Checklist](./agent-runtime-refactor-checklist.md)

---

## 一、现状盘点（功能视角）

涉及代码：

| 层 | 文件 |
|---|---|
| 工具层 `web_search` | `we-flow-agent/.../agent/tool/WebSearchTools.java` |
| 工具层 `web_fetch` | `we-flow-agent/.../agent/tool/WebFetchTools.java` |
| 搜索 provider | `we-flow-integration/.../search/`（`JinaWebSearchClient` / `DuckDuckGoWebSearchClient`） |
| 抓取 provider | `we-flow-integration/.../fetch/JinaWebFetchClient.java` |
| Agent 工具白名单 | `we-flow-workflow/.../agent/DefaultAgentSpecs.java`（`SEARCH_AGENT_TOOLS`） |

能力与缺口对照：

| 维度 | 现状 | 缺口 |
|---|---|---|
| 搜索源 | Jina Search / DuckDuckGo HTML，单一通用源 | 无多源融合、无垂直/专业源 |
| 搜索参数（agent 侧） | 仅 `query` + `maxResults`（上限 10） | 无时间范围、站内/域名过滤、语言/地区、文件类型 |
| 抓取 | 仅 Jina Reader，单 URL | 无批量、无 PDF/非 HTML 明确支持、无需登录/JS 交互页 |
| 长文处理 | **字符截断**（`maxChars`） | 无分块 + 按需二次召回，长文关键信息会被粗暴切掉 |
| 检索增强 | 无 | 无 query 改写/多查询、无 rerank、无去重、无来源质量评分 |
| 编排 | 纯 ReAct 循环，LLM 自主迭代（默认 50 轮） | 无 plan、无证据池、无 search↔fetch 收敛策略 |
| 本地/私有 | `find_files` / `read_file` / `list_dir`（子串匹配） | 无 RAG / 向量检索 / 语义召回 |

---

## 二、三个真正的瓶颈

### 1. 检索精度天花板低

Agent 侧只能传 `query` 和 `maxResults`，连最基本的「时间范围」和「site: 限定」
都没有。做时效性话题（"最近发生了什么"）或可信源约束（"只看官方文档/某权威站"）时，
模型只能把限定词硬塞进 query 字符串，命中率与可控性都差。`maxResults` 上限 10 也偏小。

### 2. `web_fetch` 是「截断」而不是「理解」

抓回内容超过 `maxChars` 就直接截断丢弃。对于长报告、长 wiki、PDF，
最相关的段落很可能落在被切掉的部分。
业界做法是 **fetch → 分块 → 对块按当前问题做语义/关键词二次召回 → 只回填最相关片段**，
既省 token 又不丢关键信息。这一层目前完全没有。

### 3. 没有「研究编排」，全靠 LLM 自由发挥

现在是标准 ReAct 循环，没有 plan、没有 evidence pool、没有去重和来源质量评分。
多源信息冲突、重复来源、浅层结果无法被系统性处理——这正是「深度研究 agent」
与「会用搜索的聊天机器人」的分水岭。

---

## 三、分层增强建议（按性价比排序）

### 第一档：低成本、立刻见效（不改架构）

> 目标：从「薄弱」到「够用」的最短路径。

- **`web_search` 增参**：`timeRange`（day/week/month/year）、
  `includeDomains`/`excludeDomains` 或 `site`、可选 `language`/`region`，
  并放宽 `maxResults` 上限。Jina / DuckDuckGo 均可承接这些参数。
- **`web_fetch` 支持批量 URL**：一次抓多个，减少 ReAct 循环轮次。
- **fetch 截断改为「分块 + 二次召回」**（哪怕先用简单关键词打分）：
  单点收益最大的改动，直接解决瓶颈 2。

### 第二档：中等成本、明显提升研究质量

- 接入 **agent-native 搜索 API**（如 Tavily / Exa）：原生支持 `answer`、
  `raw_content`、域名/时间过滤、相关性排序，比爬 DuckDuckGo HTML 稳得多。
- **多源 + rerank/去重/来源评分**，形成统一「证据池」
  （与 roadmap A4 的 run 级来源池打通）。
- **并行多 query**：一个研究问题拆成多个子查询同时搜，收敛更快。

### 第三档：高成本、看产品定位

- **垂直源工具**：学术（arXiv / Semantic Scholar）、新闻、代码搜索等
  （对应 roadmap A2 的 `SourceConnector` 抽象）。
- **RAG / 向量检索** 接入本地知识库：已有 `long_term_memories` 表与空的
  `integration.crawler` 包，是天然落点。
- **显式 plan + 反思/迭代收敛节点**（对应 roadmap A1 planner 与 A6 覆盖度校验）。

---

## 四、与报告生成路线图的关系

本方案是 [report-generation-roadmap](./report-generation-roadmap.md) 中
**A2「强化研究执行」** 的工具底座细化：

| 本文 | roadmap 对应项 |
|---|---|
| 第二档：证据池 / 去重 / 来源评分 | A4 引用与参考文献统一（run 级来源池） |
| 第三档：`SourceConnector` / 学术源 | A2 多来源 + 文献连接器 |
| 第三档：plan + 反思节点 | A1 planner / A6 覆盖度校验 |
| 第三档：RAG / 记忆检索 | B3 记忆系统接入 |

差异：roadmap 关注「报告闭环」的纵向链路；本文聚焦「检索工具本身」的横向加厚，
即使不做报告，第一档/第二档也能独立提升 `search_agent` 的可用性。

---

## 五、建议的最近三步

1. **第一档增参**：给 `web_search` 加 `timeRange` + 域名/`site` 过滤，
   `web_fetch` 支持批量 URL。改动局限在 `WebSearchTools` / `WebFetchTools`
   及对应 `*Client`，不动 graph。
2. **fetch 分块召回**：把截断逻辑替换为「分块 + 按 query 关键词/语义二次召回」，
   解决长文信息丢失（性价比最高的单点）。
3. **评估接入 Tavily/Exa**：作为 `web_search` 的可选 provider 并行验证，
   再决定是否替换默认源；为后续多源 + rerank（第二档）铺路。

> 第二/三档需结合报告生成排期推进，避免与 roadmap A 阶段重复造轮子。
