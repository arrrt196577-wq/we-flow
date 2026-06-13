# AGENTS.md

This file provides guidance for AI agents and collaborators working in this
repository.

## Project Overview

- This is a Maven multi-module Java project.
- Java version: 21.
- Spring Boot version: 3.3.6.
- Root module: `we-flow`.
- Frontend code location: `F:\agent-project\we-flow-front`.
- Modules:
  - `we-flow-common`
  - `we-flow-core`
  - `we-flow-integration`
  - `we-flow-agent`
  - `we-flow-workflow`
  - `we-flow-infrastructure`
  - `we-flow-api`
  - `we-flow-app`

## Common Commands

Run all tests from the repository root:

```bash
mvn clean test
```

Build all modules:

```bash
mvn clean package
```

Build one module and its dependencies, for example:

```bash
mvn -pl we-flow-app -am clean package
```

## Working Guidelines

- Follow the existing module boundaries and code style.
- Before changing code, inspect the relevant module `pom.xml`, package layout,
  and nearby implementations.
- Existing database table structures are documented in
  `introduction/database-schema.md`; read this file when database information is
  needed.
- Keep changes scoped to the requested behavior.
- Avoid unrelated refactors.
- When generating test code, avoid mock-based tests where practical; prefer
  tests that exercise real behavior and integrations within reasonable scope.
- Do not commit generated content such as `target/`, IDE metadata, or temporary
  files.
- For cross-module dependency changes, check both the root `pom.xml` and the
  affected module `pom.xml` files.
- For shared behavior, inspect `we-flow-common`, `we-flow-core`, and callers.
- For startup, configuration, or wiring changes, inspect `we-flow-app` and
  `we-flow-infrastructure`.

## Verification

- For small changes, run tests for the affected module.
- For cross-module changes, run `mvn clean test`.
- For packaging or startup changes, run `mvn clean package` and verify the
  application startup path.
