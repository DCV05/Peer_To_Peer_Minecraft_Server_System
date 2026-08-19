# Minecraft operations dashboard

The desktop UI is modelled after the operational language of `dllama-dashboard`: dark monospace surfaces, one-pixel separators, compact controls and state that is visible without opening menus. It does not reuse the distributed-llama domain model; every value shown here comes from the Minecraft server, host discovery, the local configuration or GitHub.

## Architecture

`view.dashboard.MinecraftDashboard` owns presentation only. It receives:

- an immutable `State` snapshot containing safe display values;
- an `Actions` implementation that delegates user intent to the application controller;
- explicit `Phase` values for long-running operations.

`view.MainFrame` remains the compatibility controller for Forge, GitHub, host discovery and existing dialogs. It converts application state into dashboard snapshots and implements the action boundary. `ForgeUtils`, `GitUtils`, `TokenStore` and `NetworkDiscoverClient` are not called from Swing components.

This separation is intentional: UI tests can exercise every dashboard state without starting Forge or accessing GitHub, while the existing integration tests continue to validate the operational services.

## Pages

| Page | Responsibility |
| --- | --- |
| Overview | Live server state, connected-player roster, lifecycle, activity and console preview |
| Servers | Current/recent server library plus create, open, import and clone entry points |
| Backups | GitHub account, explicit world pull, confirmed synchronization and collaboration |
| Network | P2P host discovery, connection address, port and VPN checklist |
| Console | Full Forge output and command input |
| Settings | Inline local-only network, port, RAM and player-cap configuration plus folder/mod actions |

Unknown telemetry is displayed as `—`; the dashboard must never invent TPS, player counts, temperatures or backup success.

## Zero-state onboarding

When no valid Forge folder is selected, Overview renders onboarding instead of empty operational cards. It provides two primary routes—create a Forge server or open an existing one—and reports the local Java runtime, GitHub session, P2P network identity and number of known server folders. Authenticated users also receive direct invitation and clone actions. Operational controls such as scan, start and console input stay hidden or disabled until a real server is loaded.

## Server state machine

```text
NO_SERVER
   │ open/create
   ▼
OFFLINE ── scan ──► DISCOVERING
   ▲                   │
   │                   ├── peer found ──► REMOTE_HOST
   │                   │
   │                   └── clear ──► SYNCING ──► STARTING ──► ONLINE
   │                                                              │
   └────────────── confirmed backup ◄── SAVING ◄── STOPPING ◄─────┘

OFFLINE ── validated folder/ZIP ──► IMPORTING ──► OFFLINE
```

Any operational failure becomes `ERROR` with an actionable inline description. Local files remain preserved, and retrying start still performs pull-before-start.

## Threading contract

- Forge, GitHub and discovery operations run outside the Swing event-dispatch thread.
- UI mutations return through `SwingUtilities.invokeLater`.
- Start is disabled during discovery, synchronization and launch.
- Stop waits for the Forge process before attempting the verified push.
- Commands are accepted only while the server is online.
- Player presence comes from Forge `list` responses every ten seconds, with join/leave events filling the interval. The active host publishes the sanitized roster in its existing P2P discovery response so non-host peers can display the same names; legacy `HERE` responses remain supported.
- World imports run off the event-dispatch thread and cannot start while a local or remote host is active.

## World import safety

The importer accepts a directory or ZIP containing exactly one `level.dat`. ZIP paths are normalized to prevent path traversal, symbolic links are rejected for folder imports, and entry/expanded-size limits protect against archive bombs. The candidate is copied into a server-local staging directory before the active world is touched. An existing configured world is moved intact to `world-import-backups/<world>-<timestamp>`; failure during final promotion rolls it back automatically.

## Forge provisioning and dialogs

`ForgeVersionWizard` replaces the legacy fixed-grid selector. It is built completely before display, loads the official Forge catalogue on a worker, filters compatible builds after a Minecraft version is chosen and installs without blocking Swing. Installation only advances when the installer exits successfully and a platform startup command exists. EULA review and explicit acceptance are the second step of the same compact wizard.

Legacy GitHub, Google and general-configuration dialogs pass through `DashboardDialogSupport`: inputs and actions receive the shared visual tokens, and windows are revealed only after their component tree and listeners exist.

## Inline local settings

The Settings page validates and writes the P2P network name, Minecraft port, exact JVM maximum (`4G` or `2048M`) and maximum-player count. Editing and saving are disabled during discovery, sync, import, startup, local hosting or remote hosting. `server.properties` and `user_jvm_args.txt` remain local-only through JGit skip-worktree flags; world data is untouched by the editor.

## GitHub world backup contract

Private repository setup starts automatically after GitHub authentication whenever a selected server is offline. Before creating a remote, the app inspects the selected tree, rejects individual files above GitHub's 100 MiB Git-object limit, and excludes runtime-only output. It stages the playable server tree—including `world/level.dat`, region files, dimensions, mods, Forge libraries and shared assets—while protecting machine-local property/JVM files.

Changed files are grouped into conservative 256 MiB commits so the initial import and later large saves remain below GitHub's per-push limit. Each commit is pushed and verified before the next batch begins; retries preserve already accepted commits and resume pending work. A backup is only reported as confirmed after every JGit remote update returns `OK` or `UP_TO_DATE`. Starts confirm/push any local pending data and then pull before launching Forge; app-controlled stops wait for Forge to finish saving before the same batched backup runs.

## Visual tokens

`DashboardTheme` is the single source of truth for colours, fonts, borders and button variants. The default palette is:

- application `#0a0a0b`;
- panel `#0d0d0e`;
- active `#18181a`;
- hairline `#1c1c1e`;
- text `#ededed`;
- muted text `#6a6a6e`;
- healthy `#4ade80`;
- error `#ef4444`;
- working `#f5a524`.

JetBrains Mono is used when installed, with the Java logical monospace font as a portable fallback. The default window is `1280×800` and the supported minimum is `1100×700`.

## Test coverage

`MinecraftDashboardTest` verifies state rendering, inline-setting validation/locking, roster names, import/pull gating, controller delegation, console commands, defensive state snapshots, deterministic navigation, onboarding behaviour and dark viewport rendering at tall window sizes. Dedicated tests cover the Forge wizard, end-to-end join/leave roster flow, player-output parsing and directory/ZIP import. The JGit integration suite clones and verifies actual nested `world/level.dat` and region data, while a disposable real-Forge gate starts, reaches `Done`, accepts `stop`, saves every dimension and exits cleanly.
