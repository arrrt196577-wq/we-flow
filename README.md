# we-flow

we-flow is a lightweight Java Agent Workflow Runtime built with Spring Boot, LangChain4j, and LangGraph4j.

The current milestone is to build a practical research-report loop:

```text
topic input
  -> lead_agent understands the task
  -> search_agent searches public information
  -> evidence is collected and organized
  -> lead_agent writes a structured report with references
```

## Current Goal

The first usable version of we-flow focuses on one clear scenario:

> Given a research topic, the agent automatically searches for related policies, references, established facts, and background information, then writes a structured report for the user.

Example topics:

* The impact of AI regulation on financial institutions
* Recent research progress in graph fraud detection
* Policy background of data security in financial technology
* Existing methods for low-label financial graph anomaly detection

## MVP Scope

The MVP does not aim to build a general-purpose autonomous agent platform.

The MVP only needs to prove this loop:

1. User provides a topic.
2. `lead_agent` analyzes the topic and decides what information is needed.
3. `search_agent` performs read-only web research.
4. Search results are converted into evidence items.
5. `lead_agent` writes a report based on the collected evidence.
6. The final report includes assumptions, findings, limitations, and references.

## Architecture

```text
we-flow
|-- we-flow-core
|   |-- agent abstractions
|   |-- chat service interfaces
|   |-- tool registry
|   `-- workspace abstractions
|
|-- we-flow-integration
|   |-- LLM integration
|   `-- web search integration
|
|-- we-flow-agent
|   |-- agent tools
|   |-- workspace file tools
|   |-- web search tools
|   `-- task delegation tool
|
|-- we-flow-workflow
|   |-- LangGraph4j agent runtime
|   |-- lead_agent graph
|   |-- search_agent graph
|   `-- subagent registry
|
|-- we-flow-api
|   `-- HTTP APIs
|
`-- we-flow-app
    `-- application bootstrap
```

## Agent Roles

### lead_agent

`lead_agent` is responsible for understanding user intent, coordinating the workflow, delegating research tasks, and writing the final response.

Responsibilities:

* Understand the user topic
* Decide whether research is needed
* Delegate research tasks to `search_agent`
* Integrate findings into a final report
* Avoid unsupported claims
* Clearly state uncertainty and limitations

### search_agent

`search_agent` is a read-only research subagent.

Responsibilities:

* Generate search directions from the assigned topic
* Search for relevant public information
* Collect policy, literature, background facts, and reference material
* Summarize findings with source links
* Return structured evidence to `lead_agent`

Restrictions:

* No file modification
* No command execution
* No task delegation
* No unsupported conclusions without evidence

### implement_agent

`implement_agent` is planned for future implementation work.

It is not part of the first research-report MVP.

Before enabling `implement_agent`, the project should define strict permissions, such as patch-based file editing and command allowlists.

## Target Report Format

The generated report should follow this structure:

```markdown
# Report Title

## 1. Executive Summary

A short summary of the topic, key findings, and overall conclusion.

## 2. Background

Important context, definitions, and known facts.

## 3. Policy or Industry Context

Relevant policies, regulations, standards, or institutional background.

## 4. Literature or Reference Review

Relevant papers, reports, articles, or technical references.

## 5. Key Findings

A structured list of findings based on collected evidence.

## 6. Risks and Limitations

Uncertainty, missing information, source limitations, or conflicting evidence.

## 7. Suggested Next Steps

Concrete recommendations for further research, implementation, or decision-making.

## 8. References

A list of sources used in the report.
```

## Evidence Contract

The first version can use a plain text evidence contract.

Suggested `search_agent` output:

```text
status: success
topic: ...
summary: ...

evidence:
- title: ...
  source: ...
  source_type: policy | paper | report | news | documentation | other
  finding: ...
  relevance: high | medium | low

key_findings:
1. ...
2. ...

risks:
- ...

recommended_report_outline:
1. ...
2. ...
```

Suggested failure output:

```text
status: error
code: ...
message: ...
```

JSON output can be introduced later after the workflow is stable.

## Non-goals for MVP

The first version will not include:

* Full DeerFlow replication
* Visual workflow editor
* Multi-user SaaS management
* Long-term memory optimization
* Arbitrary shell command execution
* Automatic file writing by implementation agents
* Complex academic database integration
* Human approval workflow

These can be added after the research-report loop is stable.

## Roadmap

### Phase 1: Research Report MVP

Goal:

Build a working loop from topic input to final report output.

Tasks:

* Improve `search_agent` prompt
* Define stable search result and evidence format
* Support multiple search queries for one topic
* Deduplicate similar search results
* Classify sources into policy, paper, report, documentation, news, or other
* Generate a structured markdown report
* Require references for factual claims
* Add tests for the full topic-to-report path

Definition of done:

* User can input a topic
* Agent searches multiple related queries
* Agent collects evidence
* Agent writes a structured report
* Report includes references and limitations

### Phase 2: Better Evidence Quality

Goal:

Make the research result more reliable and easier to verify.

Tasks:

* Add source quality scoring
* Prefer official policy sources when policy information is requested
* Prefer papers, academic pages, or technical reports when literature is requested
* Track source title, URL, snippet, and retrieval time
* Add duplicate-source filtering
* Add citation consistency checks
* Add failure handling when search results are insufficient

Definition of done:

* Report clearly separates facts from assumptions
* Report avoids unsupported claims
* Low-quality or uncertain sources are marked as such

### Phase 3: Report Customization

Goal:

Allow users to control the report style.

Tasks:

* Support report length options: brief, standard, detailed
* Support report language options: Chinese or English
* Support report types: policy brief, literature review, technical survey, decision memo
* Support user-provided focus areas
* Support user-provided exclusions

Example request:

```json
{
  "topic": "graph neural networks for financial fraud detection",
  "language": "zh-CN",
  "reportType": "literature_review",
  "length": "standard",
  "focusAreas": ["low-label learning", "financial graph anomaly detection"]
}
```

### Phase 4: Workspace-Aware Research

Goal:

Allow the agent to combine web research with local project files.

Tasks:

* Let `search_agent` inspect workspace documents
* Support user-uploaded notes or papers
* Compare external sources with local documents
* Generate reports based on both local files and web evidence

Definition of done:

* User can provide local material
* Agent can cite both web sources and workspace files
* Report distinguishes internal material from public sources

### Phase 5: Controlled Implementation Agent

Goal:

Introduce `implement_agent` safely after the research loop is stable.

Tasks:

* Define `implement_agent` permission policy
* Add patch-based file editing
* Add command allowlist
* Allow safe commands such as `rg`, `mvn test`, `git diff`, and `git status --short`
* Add audit logs for tool calls
* Add tests for permission restrictions

Definition of done:

* `implement_agent` can make small controlled code changes
* Dangerous commands are blocked
* All changes are auditable

## Development Commands

Run all tests:

```bash
mvn clean test
```

Build all modules:

```bash
mvn clean package
```

Build the application module and its dependencies:

```bash
mvn -pl we-flow-app -am clean package
```

## Current Status

we-flow is currently in early MVP development.

The immediate priority is not to add more platform features, but to complete one stable loop:

```text
topic -> research -> evidence -> report
```

Once this loop is reliable, the project can evolve toward a more general agent workflow runtime.
