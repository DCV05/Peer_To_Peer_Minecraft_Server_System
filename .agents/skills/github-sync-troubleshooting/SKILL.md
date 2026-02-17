---
name: github-sync-troubleshooting
description: Troubleshoot repository sync, authentication, and invitation workflows tied to GitHub/JGit.
---

# Scope

Use this skill for issues in `jgit/` and related UI flows (`GitWindows`, sign-in, invite/clone workflows).

# Inputs

- Error symptom (auth failure, pull/push issue, invitation mismatch, etc.).
- Optional logs or stack traces.

# Procedure

1. Inspect token/session handling (`TokenStore`) and API/JGit usage (`GitUtils`).
2. Distinguish auth issues from repository state issues.
3. Apply minimal fixes that keep existing user data and repository data safe.
4. Validate sign-in and main sync path behavior.

# Project-specific checks

- Do not expose or persist secrets in logs or committed files.
- Preserve expected behavior for clone/invite and backup repository operations.

# Output

- Root cause category.
- Fix or mitigation.
- Validation steps for sign-in and sync.

