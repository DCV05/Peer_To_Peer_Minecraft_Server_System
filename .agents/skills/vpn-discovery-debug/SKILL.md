---
name: vpn-discovery-debug
description: Diagnose and improve host discovery behavior over VPN/LAN broadcast flows.
---

# Scope

Use this skill for discovery failures, timeout behavior, UDP packet handling, and responder/client compatibility.

# Inputs

- Discovery symptom (host not found, false positives, timeout).
- Optional network assumptions (VPN in use, target port, network name).

# Procedure

1. Inspect both sender and responder flows:
   - `NetworkDiscoverClient`
   - `DiscoveryResponder`
2. Check packet format consistency and timeout/close behavior.
3. Apply minimal changes that preserve existing protocol compatibility.
4. Validate no regressions in normal discovery path.

# Project-specific checks

- Keep protocol text messages compatible (`DISCOVER: <name>`, `HERE`).
- Avoid blocking loops or socket leaks.

# Output

- Failure mode found.
- Fix or recommended mitigation.
- Validation method and expected result.

