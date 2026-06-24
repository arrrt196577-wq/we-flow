# web_search 工具问题分析

Date: 2026-06-24

## 背景

本文基于代码逐层走查（工具层 → provider 层 → 配置层 → search_agent 输出校验），
盘点 `web_search` 工具当前存在的**真实缺陷、可靠性短板与能力缺口**，并给出修复优先级。
本文**只做问题分析，不含具体代码改动**。

相关文档：

- [Search Agent 检索能力增强方案](./search-tooling-enhancement.md)
- [调研报告生成闭环与通用 Agent 演进路线图](./report-generation-roadmap.md)
- [Agent Runtime 横切逻辑重构 Checklist](./agent-runtime-refactor-checklist.md)

---

## 一、当前链路速览

```text
WebSearchTools.webSearch(query, maxResults)        // 仅这两个入参
  └─> WebSearchClient.search(WebSearchRequest{query, maxResults})
        ├─ JinaWebSearchClient         (默认, matchIfMissing=true, 需 api-key)
        └─ DuckDuckGoWebSearchClient   (爬 html.duckduckgo.com)
  └─> WebSearchResponse{query, List<WebSearchResult{title, url, snippet}>}
  └─> format() → 纯文本 "status: success / results: ..." 回传给模型
```

涉及代码：

| 层 | 文件 |
|---|---|
| 工具层 | `we-flow-agent/.../agent/tool/WebSearchTools.java` |
| provider | `we-flow-integration/.../search/JinaWebSearchClient.java` / `DuckDuckGoWebSearchClient.java` |
| 配置 | `we-flow-integration/.../search/WebSearchConfiguration.java` / `WebSearchProperties.java` |
| 模型 | `we-flow-integration/.../search/WebSearchRequest.java` / `WebSearchResponse.java` / `WebSearchResult.java` |
| 输出校验 | `we-flow-workflow/.../agent/runtime/CitationValidationMiddleware.java` |
| 白名单 | `we-flow-workflow/.../agent/DefaultAgentSpecs.java`（`SEARCH_AGENT_TOOLS`） |

配置 `we-flow.search.*`：`maxResults` 默认 5 / 上限 10、`timeout` 10s、`region=wt-wt`、
`safeSearch=moderate`、可选代理。**仓库未提交任何 `application.yml`，搜索配置完全靠外部注入。**

---

## 二、问题清单（按严重程度）

### A. 真实逻辑缺陷：搜索「成功但无可引用结果」会逼 search_agent 校验失败（P0）

`CitationValidationMiddleware` 的判定逻辑：

```java
private boolean requiresCitation(FinishContext context, CitationScan scan) {
    return scan.mentionsExternalUrl() || hasSuccessfulWebToolResult(context);
}
```

而 `WebSearchTools.format()` 无论有没有结果，**开头永远是 `status: success`**
（0 结果时只是在 `results:` 下面写 `(none)`）。由此产生矛盾：

- 只要调用过一次 `web_search`（哪怕返回 0 条），`hasSuccessfulWebToolResult` 即为 `true`
  → `requiresCitation` 为 `true`；
- 若最终结论里没有有效 `[citation:...]`（没搜到东西，自然无从引用），
  `validCitationCount == 0` → 触发 `retryOrFail("External source results require at least one citation.")`；
- `maxRetries = 1`，重试一次仍无引用 → 整个 search_agent 以
  `SEARCH_AGENT_OUTPUT_VALIDATION_FAILED` 失败。

矛盾点：重试提示语第 6 条明确写「**or None when no external sources were used**」，
允许写 `None`；但校验器并不接受 `None`——只要 web 工具返回过 `success` 就强制要有引用。

**影响**：

- 「搜索成功但结果为空 / 结果不相关」这个完全正常的场景会被判失败。
- 即使搜到结果，但 search_agent 判断结果不相关、最终结论改用 workspace 文件作答时，
  同样会被强制要求引用而失败。
- 与下面 B 叠加（DDG 被反爬时恰恰大量返回 0 结果）极易触发。

### B. Provider 可靠性 / 健壮性（P0 / P1）

1. **DuckDuckGo 静默空结果，无法区分「没搜到」与「被反爬」**
   `DuckDuckGoWebSearchClient` 爬非官方端点 `html.duckduckgo.com/html/`，
   selector 为 `.result` / `a.result__a`。一旦 DDG 改版、返回验证页或限流，
   `parseResults` 会**静默返回空列表**，工具照样回 `status: success, totalResults: 0`，
   无任何告警。`User-Agent: "we-flow/1.0"` 几乎是主动暴露 bot 身份，容易被封。

2. **Jina 超时偏短 + 启动期硬校验**
   `s.jina.ai` 实际会抓取页面，`timeout` 默认 10s 经常不够 → 频繁 `SEARCH_FAILED`。
   另外 `WebSearchConfiguration` 在缺 `api-key` 时直接抛 `IllegalStateException`：

   ```java
   if (!StringUtils.hasText(properties.jina().apiKey())) {
       throw new IllegalStateException("Missing property: we-flow.search.jina.api-key");
   }
   ```

   即 `enabled=true` 但没配 key 时，**整个应用启动失败**（结合「仓库无 application.yml」，
   是个易踩的部署坑）。

3. **错误码过于笼统**
   两个 client 把所有 `RuntimeException` 一律包成 `WebSearchException`，再到 `WebSearchTools`
   统一压成 `code: SEARCH_FAILED`。429 限流、超时、网络错误、JSON 解析失败全混成一类，
   模型与日志都无法区分，也就无法做差异化重试（限流该退避、解析失败该换源）。

### C. 能力 / 参数缺口（P1，对应增强方案第一档）

- agent 侧入参只有 `query` + `maxResults`，`WebSearchRequest` 本身也只有这两个字段。
  **没有 `timeRange`、`site`/域名过滤、`language`**，模型只能把限定词硬塞进 query 字符串。
- `maxResults` 上限硬编码为 `MAX_RESULTS_LIMIT = 10`，默认仅 5，做调研偏小。
- `region` / `safeSearch` 是**全局配置**而非按调用传参，agent 无法针对单次查询调整地区/语言。

### D. 结果质量（P2）

- 无去重（同 URL 可重复出现）、无 rerank、无来源质量评分。
- snippet 解析不到时为空字符串，`format()` 仍会输出一行空的 `snippet: `，对模型是噪音。

---

## 三、修复优先级

| 优先级 | 问题 | 理由 |
|---|---|---|
| P0 | A：成功但无引用 → 强制失败 | 纯逻辑缺陷，会让正常的「没搜到」场景直接失败，改动小、收益直接 |
| P0 / P1 | B：provider 健壮性（空结果区分、超时、错误分类） | 决定 web_search 是否能稳定可用 |
| P1 | C：增参（timeRange / site / 放宽上限） | 增强方案第一档，提升检索精度，改动局限在 `WebSearchTools` + `*Client` |
| P2 | D：去重 / 空字段清理 | 锦上添花 |

**结论**：A 与 B 是「web_search 工具问题」的核心（真实缺陷 + 可靠性），
C 是已规划的能力增强，D 为优化项。建议先处理 A、B，再按增强方案推进 C。

---

## 四、与增强方案的关系

本文聚焦 `web_search` **已有实现的缺陷与可靠性**；
[search-tooling-enhancement](./search-tooling-enhancement.md) 聚焦**检索工具能力的横向加厚**。
两者互补：先用本文的 A、B 把现有工具修稳，再用增强方案的第一/第二档把能力做强。
