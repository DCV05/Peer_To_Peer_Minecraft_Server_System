---
name: forge-server-ops
description: Safely inspect and modify Forge server lifecycle behavior in the desktop app.
---

# Scope

Use this skill for server start/stop logic, command relay, Forge setup, and related UI actions.

# Inputs

- Requested behavior change or bug report.
- Optional relevant classes (for example `ForgeUtils`, `MainFrame`, `CustomCommands`).

# Procedure

1. Inspect current server lifecycle path end-to-end.
2. Confirm effects on:
   - start flow,
   - stop flow,
   - custom command handling,
   - cloud backup trigger points.
3. Implement the smallest behavior-safe change.
4. Validate no regression in shutdown/save sequence.

# Project-specific checks

- Preserve cloud sync expectations on controlled shutdown.
- Avoid breaking in-game command forwarding and log display behavior.

# Output

- What changed in lifecycle behavior.
- Which safeguards were verified.
- Any remaining edge cases.

