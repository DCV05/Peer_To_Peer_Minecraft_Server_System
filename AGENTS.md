# AGENTS.md

This repository contains a Java desktop application (Java 21, Maven, Swing) that manages a peer-to-peer Minecraft hosting workflow with cloud sync and host discovery.

## Purpose

Use this file as persistent project guidance for Codex sessions in this repository.

## Project Modules

- `src/view/`: Swing UI, menus, dialogs, user actions.
- `src/jgit/`: GitHub/JGit integration, repository operations, token storage.
- `src/cloud/`: cloud provider interfaces and ZIP backup lifecycle.
- `src/cloud/google/`: Google Drive provider implementation.
- `src/minecraftServerManagement/`: Forge server lifecycle, custom command handling, server process integration.
- `src/vpn/`: LAN/VPN host discovery via UDP broadcast and responses.

## Build and Validation

- Prefer Maven commands from repo root:
- `mvn test`
- `mvn -q -DskipTests package` when only packaging is needed.
- Keep changes scoped and verify compile/test impact before finalizing.

## Safety Rules

- Do not break critical runtime flows:
- backup creation and restore (`ZipUtils`, provider upload/download),
- server start/stop orchestration (`ForgeUtils`, `MainFrame`),
- host discovery networking (`NetworkDiscoverClient`, `DiscoveryResponder`),
- Git sync/auth workflows (`GitUtils`, `TokenStore`).
- Treat `data/` as runtime state; avoid committing secrets, tokens, or generated artifacts.
- Avoid editing compiled binaries or generated outputs:
- `target/`
- `bin/`
- `.class` files under `src/`

## Coding Conventions

- Keep Java changes conservative and explicit.
- Preserve current behavior unless the task explicitly requests behavior changes.
- Prefer clear, minimal refactors over broad restructuring.
- Add comments only where behavior is non-obvious.

## Java Specialist Behavior

- Prefer modern Java patterns when they improve readability and safety.
- Use streams/lambdas/records/pattern matching pragmatically, not dogmatically.
- Use concurrency tools (`CompletableFuture`, virtual-thread related patterns) only when runtime behavior is clear and testable.
- Treat Spring guidance as contextual only:
- do not introduce Spring by default in this Swing/Maven project,
- only apply Spring-specific patterns if the task explicitly requires related technologies.
- Use JMH only for explicit benchmarking/performance tasks.
- Add Javadoc to complex public APIs and non-obvious logic; avoid noisy boilerplate docs for trivial methods.

## Codex Context (Java-Curated)

- Operate with pragmatic autonomy: prioritize execution, but always validate scope-critical changes before finishing.
- Explore first, then edit: inspect relevant modules and parallelize independent reads/checks when useful.
- Prefer modern Java only when it improves clarity and safety:
  - streams and collectors for data transforms,
  - records/sealed/pattern matching where they simplify domain modeling,
  - `CompletableFuture` and virtual-thread style concurrency only with explicit thread-safety reasoning.
- Require JUnit 5 coverage for functional behavior changes; add parameterized tests when they reduce duplication and increase scenario coverage.
- Keep secrets out of git. Use `.env.example` templates and local untracked `.env` files for credentials/tokens.
- React/TypeScript/Vite-oriented guidance is not applicable by default in this repository.
- Spring guidance is contextual only:
  - do not introduce Spring by default,
  - only apply Spring patterns if requested by the user or already present in touched modules.

## Module Checklists

### `view`

- Confirm UI state transitions still match server/process state.
- Confirm menu actions still call the intended integration methods.

### `jgit`

- Confirm auth/session state is still consistent across calls.
- Confirm remote operations handle failures without corrupting local repo state.

### `cloud` / `cloud/google`

- Confirm backup ZIP filtering and file paths remain valid.
- Confirm upload/download calls still preserve world/mod/config continuity.

### `minecraftServerManagement`

- Confirm startup, shutdown, and command relay behavior remain coherent.
- Confirm custom command handlers do not regress safety checks.

### `vpn`

- Confirm discovery packets and response parsing remain compatible.
- Confirm timeout behavior and socket closure paths are safe.

## Preferred Agent Workflow

- Inspect relevant module(s) first.
- Implement minimal, targeted changes.
- Run Maven validation suitable for scope.
- Summarize changed files and remaining risks.
