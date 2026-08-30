# Endershare

Endershare (formerly Peer To Peer Minecraft Server System) is a desktop application designed to solve one of the most common problems when playing **Minecraft Java Edition** with friends:  
the server depends entirely on a single host who is not always available to keep it running or even online at the same time as the rest of the group.

This application allows a group of players to **share the responsibility of hosting a Minecraft server**, enabling any member to start the server on their own machine and continue playing **exactly where the progress was left**, without requiring the original host to be online.

---

## Key Features

- Create and manage a Minecraft server easily (currently **Forge** and **Fabric**, with **NeoForge** planned for the future)
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

The desktop interface uses an operations-first layout with persistent pages for **Overview**, **Servers**, **Backups**, **Network**, **Console**, and **Settings**. Server, host-discovery and GitHub states remain visible while background work is running. The visual and state architecture is documented in [`docs/DASHBOARD_UI.md`](docs/DASHBOARD_UI.md).

#### Start / Stop Server
- Starts the server using the configured settings
- Displays a live console for logs and command execution
- Shows the connected-player roster from real Forge `list` responses
- When the server is running, the button switches to **Off**
- Stopping the server using this button triggers a cloud save

#### Import / Pull World
- Imports a Minecraft world folder or ZIP into the selected offline Forge server
- Preserves the previous world in `world-import-backups` and rolls it back if promotion fails
- Pulls the latest confirmed GitHub world only while no local or remote host is active

#### Inline Server Settings
- Edits the P2P network, server port, RAM limit and maximum players directly in the **Settings** page
- Validates values before writing and locks the editor while a local or remote host is active
- Keeps `server.properties` and `user_jvm_args.txt` local to each host

**Important:**  
Use the dashboard stop control (or enter `stop` in its console). Endershare waits for Forge to finish saving and only then starts the verified cloud backup.

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

#### Automatic Private Server Backup
After a GitHub account is connected, opening an offline server automatically creates and links a **private** repository (Git installation is not required). The same check runs before start, after stop, and before the application exits.

Large existing servers are not staged as one enormous push. Endershare inspects the complete tree first, divides changed files into conservative commit batches, pushes each batch separately, and continues only after the remote confirms it. A network failure leaves the accepted batches in GitHub and the pending batch locally, so **Retry private backup** resumes the process.

Generated logs, crash reports, temporary session locks, and local import rollback folders are excluded. The playable server state—including the world, mods, configuration, Forge libraries, and startup files—remains versioned. Files over GitHub's 100 MiB per-object limit are rejected before a remote repository is created, with the blocking path shown in the dashboard.

To use this feature, sign in with a GitHub **classic personal access token** carrying the `repo` scope. Endershare validates the token against GitHub and derives the account identity itself.

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
2. A **classic personal access token** with the `repo` scope
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

3. **Log in to GitHub**
   Generate a classic token with the `repo` scope and log in. Endershare creates the private repository and uploads the offline server automatically.

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

## Final Notes

This project is under active development and represents an evolving approach to casual multiplayer Minecraft hosting.

Thank you for reading and for trying out this application.
