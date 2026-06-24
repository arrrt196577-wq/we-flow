# web_search / web_fetch 输出改为 JSON 契约方案（2026-06-24）

## 背景

承接 [web_search 工具问题分析](./web-search-tool-issues.md) 中的「输出格式（result）规范性」一节。
当前 `web_search` / `web_fetch` 返回给模型的是「伪 YAML」纯文本，存在不可靠解析、
字段语义不清、缺引用句柄等问题。本方案采用**方案 B：将这两个工具的 result 统一改为严格 JSON**，
彻底解决可解析性与转义问题。本文为**可行方案，落地前需确认第四节的拍板点**。

相关文档：

- [web_search 工具问题分析](./web-search-tool-issues.md)
- [Search Agent 检索能力增强方案](./search-tooling-enhancement.md)

---

## 关键前提：`status:` 约定是全局的

走查发现 `status: success` / `status: error` 这套行格式**并非 web_search/web_fetch 专有**，
还被以下处使用：

- `TaskDelegationTool`（`status: success\nsubAgent: ...`）
- 子 agent 运行时错误（`AGENT_MAX_LOOPS_EXCEEDED`、`AGENT_LLM_TIMEOUT`、`LEAD_TOOL_CALL_LIMIT_EXCEEDED`）
- workspace 工具（`find_files` / `read_file` / `list_dir`）

因此「只把 web_search/web_fetch 改成 JSON」会让系统中**同时存在两套输出约定**。
本方案有意将范围限制在这两个 web 工具（分期推进），其余保持行格式不变。

---

## 一、改动范围与影响面

| 文件 | 改动 | 说明 |
|---|---|---|
| `we-flow-agent/.../tool/WebSearchTools.java`（`format()`/`error()`） | 改为输出 JSON | 核心 |
| `we-flow-agent/.../tool/WebFetchTools.java`（`format()`/`error()`） | 改为输出 JSON | 核心 |
| `we-flow-workflow/.../agent/runtime/CitationValidationMiddleware.java`（`isSuccessfulToolResult()`） | 由「按行找 `status: success`」改为**解析 JSON** 取 `status` | **必须同步，否则引用校验失效**；它已按 `toolName` 过滤这两个工具，只需对其走 JSON 解析 |
| `we-flow-agent/pom.xml` | 显式声明 `jackson-databind` | 目前靠 `we-flow-integration` 传递依赖，建议显式 |
| `we-flow-agent/.../tool/WebSearchToolsTest.java` / `WebFetchToolsTest.java` | 断言改为 JSON | 单测 |
| `we-flow-workflow/.../chat/LangGraph4jAgentChatServiceTest.java` | 把多处**伪造工具输出**（`status: success\ntotalResults...` 等）改为 JSON | test double 需与新格式一致，否则中间件判定会变 |
| `we-flow-workflow/.../tool/AgentToolConfigurationTest.java` | 核对 | 大概率只验工具注册、不验格式，待确认 |

**不动**：`WebSearchClient` / `WebFetchClient` 及各 `*Client` 和其测试（它们解析 provider 响应，
与对模型输出格式无关）；`TaskDelegationTool`、workspace 工具、运行时错误码（本次范围外，保持行格式）。

---

## 二、目标 JSON 契约

### web_search 成功

```json
{
  "status": "success",
  "query": "spring boot 3.5 release notes",
  "returnedResults": 2,
  "results": [
    {
      "rank": 1,
      "title": "Spring Boot 3.5 Release Notes",
      "url": "https://github.com/spring-projects/spring-boot/releases",
      "source": "github.com",
      "snippet": "..."
    }
  ]
}
```

无结果：`"returnedResults": 0, "results": []`（与「有结果」天然区分，不再是裸 success）。

### web_fetch 成功

```json
{
  "status": "success",
  "url": "https://example.com/page",
  "requestedUrl": "https://example.com/page",
  "title": "Example",
  "contentLength": 1234,
  "truncated": false,
  "content": "正文……\n换行被 JSON 转义，得以完整保留"
}
```

### 失败（两个工具统一）

```json
{ "status": "error", "code": "SEARCH_FAILED", "message": "..." }
```

`web_search` 错误码：`INVALID_ARGUMENT` / `SEARCH_FAILED`；
`web_fetch` 错误码：`INVALID_ARGUMENT` / `FETCH_FAILED`。

> 顺带收益：JSON 字符串会自动转义引号/换行，`web_fetch` 正文**不再需要拍平换行**，
> snippet 含 `:` 也不会破坏结构。这是方案 B 相对伪 YAML 的核心优势。

---

## 三、实现方式

- 使用 Jackson `ObjectMapper`（已在 `we-flow-integration` 使用，agent 模块可传递获得，建议显式加依赖）。
- 在 tool 包新增一个**轻量序列化 helper**（共享一个配置好的 `ObjectMapper`），两个工具复用，避免重复。
- 序列化配置：
  - `JsonInclude.NON_EMPTY`：空 `snippet` / `source` 自动省略，去噪。
  - **pretty-print 缩进 2 空格**：对模型可读、便于调试，token 代价可忽略。
- 新增字段：`rank`（序号）、`source`（从 `url` 解析 host，零成本）；`web_fetch` 增 `requestedUrl`。
  `publishedDate` / 相关性分**本次不加**，等接入支持的 provider 再补，避免填假值。
- 日志格式（`Tool result: ... status=success ...` 的 key=value）**不动**，它独立于对模型的返回。

---

## 四、待拍板点

1. **混合约定**：本次仅 web_search/web_fetch 转 JSON，其余工具与运行时错误保持行格式——是否接受？
   （彻底统一是更大的工程，建议分期。）
2. **字段集**：web_search 加 `rank`+`source`、web_fetch 加 `requestedUrl`，`publishedDate` 暂缓——是否同意？
3. **输出形态**：pretty-print（推荐）还是 compact 紧凑 JSON？

---

## 五、不在本次解决（划清边界）

- 问题 A（「成功但无结果被强制要求引用」）属中间件逻辑，不是格式问题。
  但转 JSON 后，中间件可直接读 `returnedResults == 0` 做豁免，**为 A 的修复扫清障碍**。
- 问题 B（provider 反爬 / 超时 / 错误码细分）、问题 C（检索增参）不在本次范围。

---

## 六、验证

改完运行：

```bash
mvn -pl we-flow-agent -am test
mvn -pl we-flow-workflow -am test
```

覆盖工具单测 + 引用校验中间件 + 图编排测试。
