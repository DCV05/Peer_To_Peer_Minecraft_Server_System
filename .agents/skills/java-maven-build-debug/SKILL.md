---
name: java-maven-build-debug
description: Build, test, and diagnose Java/Maven issues in this repository with minimal and safe steps.
---

# Scope

Use this skill for compile failures, test failures, packaging problems, or dependency/build diagnostics.

# Inputs

- User goal or error message.
- Optional target command (`mvn test`, `mvn -q -DskipTests package`, etc.).

# Procedure

1. Confirm repository root and read `pom.xml`.
2. Run the smallest Maven command that reproduces the issue.
3. Identify root cause before proposing fixes (dependency, code, plugin, environment).
4. Prefer minimal code changes over broad refactors.
5. Re-run the relevant Maven command to validate.

# Project-specific checks

- Ensure no accidental edits to `target/`, `bin/`, or committed `.class` artifacts.
- Keep Java 21 compatibility.

# Output

- Clear root-cause summary.
- Exact fix applied (or recommended).
- Validation command and result.

