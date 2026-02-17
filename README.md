# Peer To Peer Minecraft Server System

Peer To Peer Minecraft Server System is a desktop application designed to solve one of the most common problems when playing **Minecraft Java Edition** with friends:  
the server depends entirely on a single host who is not always available to keep it running or even online at the same time as the rest of the group.

This application allows a group of players to **share the responsibility of hosting a Minecraft server**, enabling any member to start the server on their own machine and continue playing **exactly where the progress was left**, without requiring the original host to be online.

---

## Key Features

- Create and manage a Minecraft server easily (currently **Forge**, with **Fabric** and **NeoForge** planned for the future)
- Start and stop the server directly from the application
- Access the server console to view logs and execute commands
- Configure common server settings such as:
  - Allocated RAM
  - Server port
  - Mods management
- Automatic cloud synchronization of server data when the server is stopped
- Seamless continuation of the server from another computer
- GitHub-based cloud storage (Google Drive and other providers planned)

---

## How It Works

The core idea is simple:

- When the server is stopped using the application, the full server state (world, mods, configuration) is saved to the cloud.
- When another user starts hosting the server, the application automatically downloads the latest state.
- The game continues from the exact point where it was left, regardless of who is hosting.

---

## Application Workflow

### Opening or Creating a Server

From the application menu:

- **File > Open Minecraft Server**  
  Open an existing Forge server and manage it using the application.

- **File > New Minecraft Server**  
  Create a new server from scratch by selecting:
  - Minecraft version
  - Forge version
  - Destination folder  
  No advanced server knowledge is required.

---

### Server Dashboard

Once a server is opened or created, a dashboard is displayed with the following options:

#### Start / Stop Server
- Starts the server using the configured settings
- Displays a live console for logs and command execution
- When the server is running, the button switches to **Off**
- Stopping the server using this button triggers a cloud save

**Important:**  
Stopping the server manually using `/stop` is not recommended, as it will not trigger the cloud synchronization.

---

#### Server IP Status
A text field indicating:
- Whether the server is currently offline
- Or, if online, the IP address to connect from Minecraft

---

#### Refresh Hosts
Re-scans the network to check if another user is currently hosting the server.

---

#### Open Mods Folder
Quick access to the server's mods folder to easily add or update mods.  
Mods are also synchronized through the cloud.

---

#### Create Server Repository
Creates a local Git repository (Git installation not required) and a remote GitHub repository where the server data will be stored.

To use this feature, you must log in with:
- A GitHub **classic personal access token**
- GitHub username
- Email address

It is recommended to use a separate GitHub account to avoid mixing personal or professional repositories.

---

#### Configuration
Opens a configuration window with local settings:

- **Network Name**  
  All members of the same server group must use the same network name for proper host discovery.

- **Server Port**  
  Defaults to `25565`.  
  If changed, all members must use the same port and connect using `IP:PORT`.

RAM allocation is local and can be configured independently by each host.

---

## Cloud Storage and GitHub Integration

### GitHub Requirements

To use GitHub as a cloud storage backend, you need:

1. A GitHub account  
   (Using a secondary account is recommended)
2. A **classic personal access token** with full permissions  
   Official guide:  
   https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens

---

### Git Menu Options

Once logged in, the **Git** menu provides:

- View profile information used for authentication
- Clone a server repository you have been invited to
- Invite another GitHub user as a server host
- View and accept server invitations
- Log out

---

## Network Name and VPN Requirement

To allow host discovery and server connectivity:

- All members must use the **same network name** in the application
- A **Peer-to-Peer VPN** is required, such as:
  - Hamachi
  - Radmin
  - Tailscale

The specific VPN software does not matter, as long as all users are connected to the same virtual LAN/WLAN.

---

## Requirements

- **Java 21 or newer**
- A Peer-to-Peer VPN configured and active for all participants

---

## Getting Started (From Scratch)

1. **Download the application**  
   From the Releases section:
   - Windows: install using the installer
   - Unix systems: extract the archive and run the JAR

2. **Create a new server**  
   `File > New Minecraft Server`  
   Select Minecraft and Forge versions.

3. **Log in to GitHub and create the repository**  
   Generate a classic token, log in, and click **Create repository**.

4. **Invite server members** (multiplayer)  
   `Git > Add hosting user` and enter GitHub usernames.

5. **Accept invitations** (invited members)  
   - `Git > Server invitations`
   - Accept the invitation
   - Clone the repository into an empty directory

6. **Configure and start the server**  
   - Ensure network name and port match for all members
   - Start the server using the **On** button
   - Wait until the console and IP address appear
   - Connect from Minecraft and start playing

---

## Troubleshooting (Connection Issues)

If you cannot join the server from the game:

1. Verify that the VPN is running and all members are on the same network
2. Check firewall settings:
   - Ensure Minecraft is allowed
   - Ensure the configured port allows inbound and outbound TCP and UDP traffic

Disabling the firewall temporarily may work but is done at your own risk.

---

## Codex Setup (IDE Extension / CLI)

This repository is configured for the current Codex standard using:

- `AGENTS.md` at repository root for persistent project instructions
- `.codex/config.toml` for project-level Codex configuration
- `.agents/skills/` for reusable, task-focused skills

### Why `.codex` and not `.openai`?

Codex currently documents project configuration around `.codex` and `AGENTS.md`.
If you expected `.openai`, treat `.codex` as the canonical replacement.

### Files Added for Codex

- `AGENTS.md`: repository behavior and safety conventions for this Minecraft system
- `.codex/config.toml`: profile defaults, approvals/sandbox policy, MCP entries, notify hook
- `.codex/hooks/notify.ps1`: optional completion hook writing to `data/codex-notify.log`
- `.codex/mcp.env.example`: template for MCP tokens/endpoints (no secrets committed)
- `.agents/skills/*`: domain skills for Maven/build, Forge ops, GitHub sync, and VPN discovery

### Context Strategy

- This repository uses a Java-curated prompt strategy instead of importing generic multi-stack prompts literally.
- Persistent context lives in:
  - `AGENTS.md` (project-wide guardrails and workflow)
  - `.agents/skills/java-developer/SKILL.md` (Java specialist execution protocol)
  - `.agents/skills/java-developer/references/universal-java-context.md` (curated universal Java meta-context)
- React/TypeScript/Vite-oriented guidance from generic prompt packs is treated as non-default for this Java Swing project.

### `.env` Safety Guide

- Keep real credentials in local untracked `.env` files.
- Commit only template files such as `.env.example` or `*.env.example`.
- Before committing, run `git status` and ensure no real secret file is staged.

### Useful Slash Commands

- `/status` to inspect active context and session state
- `/mcp` to list configured MCP servers/tools
- `/debug-config` to inspect effective config (if available in your Codex build)
- `/review` to request a working-tree review
- `/init` to scaffold/update local instructions in other folders

### Java MCP Servers (Configured)

| MCP Server | Purpose | Default | Prerequisites |
| --- | --- | --- | --- |
| `openai_docs` | Official OpenAI documentation lookup | Enabled | None |
| `context7` | Up-to-date library/framework docs | Enabled | `npx` available |
| `github` | Repo/issues/PR/search operations | Enabled | Docker + `GITHUB_PERSONAL_ACCESS_TOKEN` |
| `jetbrains` | IDE-aware context and tooling | Disabled | Local JetBrains MCP endpoint |
| `sonarqube` | Static analysis findings and quality gates | Disabled | SonarQube MCP endpoint + token |

### Skills in this Repo

- `java-maven-build-debug`
- `java-developer`
- `forge-server-ops`
- `github-sync-troubleshooting`
- `vpn-discovery-debug`

### Quick Troubleshooting

- If Codex does not load project instructions, ensure you opened the repository root and that `AGENTS.md` is present.
- If MCP tools do not appear under `/mcp`, verify `.codex/config.toml` is loaded and the project is trusted.
- If GitHub MCP fails to start, confirm Docker is running and `GITHUB_PERSONAL_ACCESS_TOKEN` is set.
- If Context7 fails, verify Node.js/`npx` is available in your shell.
- If SonarQube or JetBrains MCP is unavailable, keep them disabled until URL/token/IDE endpoint is validated.
- Use `.codex/mcp.env.example` as the baseline for local env setup; do not commit filled secret files.
- If the notify hook does not log events, check whether `data/codex-notify.log` can be created.
- If skills do not show up, ensure each skill folder contains `SKILL.md` and restart the Codex session.

---

## Final Notes

This project is under active development and represents an evolving approach to casual multiplayer Minecraft hosting.

Thank you for reading and for trying out this application.
