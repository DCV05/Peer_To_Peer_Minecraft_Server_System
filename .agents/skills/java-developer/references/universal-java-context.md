# Universal Java Context (Curated for Codex)

This reference distills the "universal Java meta-prompt" into reusable operational guidance.

## Core execution model

1. Analyze request and module boundaries
2. Detect stack and version constraints
3. Design minimal safe change
4. Implement with explicit failure handling
5. Validate with the smallest sufficient test/build pass

## Quality baseline

- Prefer clear, modern Java over boilerplate
- Preserve behavior unless change is requested
- Cover functional changes with JUnit 5 tests
- Use parameterized tests when they improve scenario coverage
- Include Javadoc only for non-obvious public APIs/logic

## Context switches

- Spring stack detected: apply layered architecture conventions and validation/exception boundaries.
- Non-Spring stack detected: keep plain Java approach and avoid framework introduction.
- Performance task detected: add profiling/benchmarking path (JMH only if explicitly required).

## Reliability checks

- Handle I/O and process/network failures explicitly
- Avoid hidden side effects and broad refactors
- Keep critical data/sync flows intact
- Validate build/test commands before finalizing

## Anti-pattern quick list

- Field injection
- Blind `Optional#get()`
- Leaking persistence models into external contracts
- Framework additions without contextual need
- Skipping tests on behavior changes
