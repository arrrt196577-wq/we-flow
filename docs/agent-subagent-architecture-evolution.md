# Agent/Subagent Architecture Evolution

## Background

The first version does not need to fully replicate DeerFlow. A lighter and safer
evolution path is to make the runtime "DeerFlow-like" first:

- business code declares agent configuration;
- runtime creates graphs from that configuration;
- graph construction details are centralized in one factory.

With this shape, the explicit graph remains the primary runtime representation.
Future evolution should happen by keeping graph construction centralized while
moving cross-cutting business semantics into middleware.

## Core Direction

- Graph topology belongs to the agent runtime mechanism and should be uniformly
  encapsulated.
- Agent configuration belongs to business declaration and should be provided by
  each lead agent or subagent.
- Lead agents, search agents, and implement agents should all be created through
  the same `AgentSpec + AgentGraphFactory` path.
- The lead agent delegates work to subagents through the `delegate_task` tool.
- Subagents should be independent compiled graphs, not simple synchronous
  placeholder executors.
- Explicit graph topology should remain visible and intentional at the runtime
  layer; the goal is not to replace it with an opaque agent builder.

## Subagent Taxonomy

There are exactly two subagent types, divided by capability and blast radius
rather than by business role:

- `search_agent` — the **research type**. Read-only investigation: web search,
  web fetch, and workspace file discovery. It never writes files, never runs
  commands, and never delegates. Safe to run in parallel and on untrusted input.
- `implement_agent` — the **execution type**. It reads, edits files
  (`apply_patch` / `write_file`), and runs allowlisted commands
  (`run_command`). It carries write/execute blast radius and is gated
  accordingly.

This is deliberately close to DeerFlow's two built-in subagents
(`general-purpose` + `bash`), but with a stricter read-only vs. write boundary
as the dividing line.

Roles such as planner and reporter are **not** separate subagents. Planning and
report writing are handled by the lead agent's orchestration plus reusable
**skills** (domain templates and methodology loaded on demand). New domain
behavior is added as a skill or a config-defined custom agent, not as a new
hardcoded role subagent. This keeps the subagent surface small and the topology
stable.

## Core Interfaces

### AgentSpec

`AgentSpec` describes an agent's business-level declaration:

- `code`
- `name`
- `type`: `LEAD` or `SUB`
- `systemPrompt`
- `tools`
- `maxToolIterations`
- `permissions`
- `stateSchema`

### AgentGraphFactory

`AgentGraphFactory` owns graph construction:

```text
create(AgentSpec spec) -> CompiledGraph
```

Business code should not manually define graph nodes and edges for every agent.

### SubAgentRegistry

`SubAgentRegistry` owns subagent discovery and lookup:

```text
listDefinitions()
findByCode()
```

The `delegate_task` tool should resolve the requested `subagentCode` through
this registry and execute the corresponding graph-backed subagent.

## Evolution Plan

### Phase 1: Lightweight Configuration

Continue using the current LangGraph4j ReAct graph shape:

```text
model -> tool -> model -> ... -> end
```

The main change is to hide this topology inside a common `AgentGraphFactory`.
Business code should only declare agent-specific configuration such as prompt,
tool list, maximum tool iterations, and permission policy.

The initial configured agents can be:

- `lead_agent`
- `search_agent`
- `implement_agent`

### Phase 2: Graph-Backed Subagents

Replace the current `simple_task_subagent` placeholder with real compiled
subagent graphs. These two agents are the **complete subagent taxonomy** (see
"Subagent Taxonomy" above); do not add role-specific subagents such as planner
or reporter.

`search_agent` should focus on read-only investigation:

- fact checking before complex tasks;
- web search;
- workspace file discovery;
- outputting evidence, solution options, and risks;
- no file writes or high-risk command execution.

`implement_agent` should focus on implementation:

- reading and modifying files;
- running allowed commands;
- running tests;
- outputting changed files, executed commands, and verification results.

### Phase 3: Externalized Agent Configuration

Move agent declarations out of Java hardcode over time. Configuration can later
live in files, a database, or a registry.

Externalized fields should include:

- agent code;
- agent name;
- system prompt;
- available tools;
- permission policy;
- maximum iterations;
- state schema.

After this phase, adding a subagent should require adding configuration rather
than writing new graph construction code.

### Phase 4: Middleware And Hooks

Move cross-cutting runtime behavior out of business code and into unified
middleware or hooks.

Candidate capabilities include:

- logging;
- token accounting;
- command permission checks;
- tool invocation auditing;
- error recovery;
- memory injection;
- human approval;
- checkpointing and tracing.

This phase moves the architecture closer to DeerFlow's middleware chain model.

### Phase 5: Business Semantic Middleware

Keep explicit graph construction as the stable runtime foundation. Later
evolution should focus on moving business cross-cutting semantics into
middleware instead of replacing the graph builder.

Candidate middleware semantics include:

- task planning policy;
- delegation policy;
- context compression and handoff rules;
- tool selection hints;
- business approval gates;
- domain-specific tracing tags;
- result summarization and normalization.

The stable contract should remain:

```text
AgentSpec remains unchanged
SubAgentRegistry remains unchanged
delegate_task remains unchanged
AgentGraphFactory continues to build explicit graphs
business cross-cutting semantics move into middleware
```

## Recommended First Version

The recommended first target is:

```text
lead_agent
  -> delegate_task(search_agent)
  -> delegate_task(implement_agent)

search_agent
  -> web_search
  -> find_files / list_dir / read_file

implement_agent
  -> read_file
  -> apply_patch / write_file
  -> run_command(restricted)
```

This keeps the implementation focused while establishing the right extension
points for later evolution.

## Expected Benefits

- `simple_task_subagent`, web search prompts, and tool loop policies are no
  longer mixed inside `AgentGraphFactory`.
- New agents and subagents do not need repeated graph node and edge code.
- Future evolution can keep explicit graph construction stable while moving
  business cross-cutting semantics into middleware.
- Lead agent and subagent responsibilities become clearer.
- Permission strategy, tool access, and runtime behavior become easier to
  reason about and evolve.
