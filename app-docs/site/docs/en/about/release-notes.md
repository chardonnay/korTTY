# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.24.0

### Connection Manager

- **New layout** — the Connection Manager shows a library on the left (all connections, favorites, recently used, open now, folders, tags, teamwork), the chosen connections in the middle and an inspector on the right, with every detail of the selected connection and quick edits of its name and tag. The toolbar uses icons in the style of the icon toolbar, and the list shows each connection's address, tag and marks for jump server, tunnels, isolation and incognito instead of emoji. See [Connection Manager](../features/connections.md#connection-manager).
- **Map** — **Map** shows the connections as a network plan: a zone per folder in its tab color, jump-server routes and tunnels as lines, double-click connects and dragging a node into another zone moves the connection into that folder. See [List and map](../features/connections.md#list-and-map).
- **Favorites** — mark connections with the star to find them under **Favorites**; teamwork connections can be favorites too.
- **Teamwork beside local connections** — the general lists show teamwork connections too, marked with a small people symbol and read-only. See [Library](../features/connections.md#library).
- **OS symbols** — give each server the symbol of its operating system (Windows, macOS, Linux distributions, ChromeOS, Unix, BSD, z/OS) or one of your own images, shown in the list, on the map and in the inspector. See [OS symbols](../features/connections.md#os-symbols).
- **Map as PDF** — **PDF** on the map saves it as a vector PDF with the korTTY logo, in landscape or portrait.
- **Overview export** — **Export overview** writes your servers as JSON, YAML, TOML or XML, with the parts you choose and never a password or key. See [Exporting an overview](../features/connections.md#exporting-an-overview).
- **Inspector** — the route from this computer through the jump server, security as chips, usage bars for the last 14 days and an SFTP button.
- **Toolbar for an empty connection row** — while **View → Connection toolbar** is switched on but holds no servers or groups yet, the row stays visible and offers **Add connection**, which opens customizing on the connections catalog. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).

### Serial connections

- **Serial ports** — the new **Serial** protocol opens a terminal on a serial port, like PuTTY's serial sessions: a router or switch console, a microcontroller board or a USB-to-serial adapter, on Windows, macOS and Linux. Pick the port from the list or type it, and set the speed, data bits, parity, stop bits, flow control, what Enter sends, DTR/RTS and local echo. **Send Break** is in the terminal's context menu and in the Connections menu, and can get a key in the keyboard settings. Serial sessions from MobaXterm and PuTTY Connection Manager files are imported too. See [Serial connections](../features/serial.md).

### Terminal

- **Clipboard protocol of kitty (OSC 5522)** — programs that speak kitty's clipboard protocol, such as Neovim and `kitten clipboard`, can copy to the clipboard under the OSC 52 setting, and with the new **Let programs in the terminal ask to read the clipboard (OSC 5522)** setting they can ask to read it. Every read asks you first, **Deny** is the default, and a paste into a program that asked for paste events counts as your consent. Only text is exchanged; the strict terminal mode drops the protocol, and the policy key `allow-osc5522-clipboard-read = false` forbids reading. See [Programs reading the clipboard](../features/terminal-notifications.md#programs-reading-the-clipboard-osc-5522).
- **Scrollback memory limit** — the new **Scrollback memory** slider in the Terminal settings sets the most memory the scrollback of one pane may use, from 1 to 50 MB (10 MB by default), next to the line count; whichever limit is reached first drops the oldest lines. A saved change applies to open terminals at once. See [Terminal settings](../reference/settings/terminal.md).
- **Scrollback compression** — while a terminal has no output, korTTY compresses the scrollback lines that are not on screen in the background, so the same memory holds several times as many lines. A line is unpacked as soon as you scroll to it. Switch it off with **Compress the scrollback in the background while idle**. See [Terminal settings](../reference/settings/terminal.md).
- **Pixel size for image tools over SSH** — SSH sessions, isolated ones included, now tell the server the size of the terminal in pixels, so image tools that use the kitty graphics protocol size their images correctly instead of seeing 0×0.

### AI

- **Disable the AI agent per connection** — for systems such as production servers, switch on **Disable AI agent for this connection** on the **AI** tab of the connection editor. Every agent run on that connection is then refused, from the menu, the `agent ...` shortcut, accepted plans, swarm members and scheduled AI Agent jobs; **Ask Agent**, **AI Planning** and the other AI features stay available. See [Connections without the agent](../features/ai-tools.md#connections-without-the-agent).
- **Search the connection skills** — a search field above the AI Skills list in the connection editor filters them by name, description and tags.

### Snippets

- **Snippet Manager windows no longer block the main window** — the terminals stay usable while a Snippet Manager window is open, also while it asks a question or runs a Full code analysis. **Tools → New Snippet Manager Window** opens a further one, and **Move to new window** on a tab moves a snippet or a folder analysis into a window of its own, for example on a second screen. See [Opening the Snippet Manager](../features/snippets.md#opening-the-snippet-manager).

### Coding agents

- **Cline and Antigravity CLI** — the Coding Agents panel now recognises **Cline** and Google's **Antigravity CLI** (`agy`), the successor to Gemini CLI; the rules of every other agent were re-checked against its latest version, and Gemini CLI's folder-trust and sign-in dialogs now count as waiting for you. See [What it detects](../features/coding-agents.md#what-it-detects).

### Fixes

- **Copying from the AI chat** — ++cmd+c++ / ++ctrl+c++ and ++cmd+a++ / ++ctrl+a++ now work after clicking into a chat message, and the chat's scrollbar is wider and easier to see.
- **Wide dialogs open at their size in the AtlantaFX designs** — under an AtlantaFX design, a dialog laid out wider than 600 px (such as the settings, **Compare folders** in the SFTP manager or the **Logfile Manager**) opened squeezed or grew after it appeared; it now opens as wide as in the Normal design, and a long message still wraps.
