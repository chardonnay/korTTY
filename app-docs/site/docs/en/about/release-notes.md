# Release notes

What changed in the current release. The version this guide was built for is shown in the footer.

## v2.23.0

### Connection Manager

- **New layout** — the Connection Manager shows a library on the left (all connections, favorites, recently used, open now, folders, tags, teamwork), the chosen connections in the middle and an inspector on the right, with every detail of the selected connection and quick edits of its name and tag. The toolbar uses icons in the style of the icon toolbar, and the list shows each connection's address, tag and marks for jump server, tunnels, isolation and incognito instead of emoji. See [Connection Manager](../features/connections.md#connection-manager).
- **Map** — **Map** shows the connections as a network plan: a zone per folder in its tab color, jump-server routes and tunnels as lines, double-click connects and dragging a node into another zone moves the connection into that folder. See [List and map](../features/connections.md#list-and-map).
- **Favorites** — mark connections with the star to find them under **Favorites**; teamwork connections can be favorites too.
- **Teamwork beside local connections** — the general lists show teamwork connections too, marked with a small people symbol and read-only. See [Library](../features/connections.md#library).
- **OS symbols** — give each server the symbol of its operating system (Windows, macOS, Linux distributions, ChromeOS, Unix, BSD, z/OS) or one of your own images, shown in the list, on the map and in the inspector. See [OS symbols](../features/connections.md#os-symbols).
- **Map as PDF** — **PDF** on the map saves it as a vector PDF with the korTTY logo, in landscape or portrait.
- **Overview export** — **Export overview** writes your servers as JSON, YAML, TOML or XML, with the parts you choose and never a password or key. See [Exporting an overview](../features/connections.md#exporting-an-overview).
- **Inspector** — the route from this computer through the jump server, security as chips, usage bars for the last 14 days and an SFTP button.

### Serial connections

- **Serial ports** — the new **Serial** protocol opens a terminal on a serial port, like PuTTY's serial sessions: a router or switch console, a microcontroller board or a USB-to-serial adapter, on Windows, macOS and Linux. Pick the port from the list or type it, and set the speed, data bits, parity, stop bits, flow control, what Enter sends, DTR/RTS and local echo. **Send Break** is in the terminal's context menu and in the Connections menu, and can get a key in the keyboard settings. Serial sessions from MobaXterm and PuTTY Connection Manager files are imported too. See [Serial connections](../features/serial.md).

### On Windows

- **PowerShell and Command Prompt in the toolbar** — on Windows, the Terminal group of the icon toolbar gets **New PowerShell** and **New Command Prompt** right after **New local shell**, each with its own icon; saved layouts gain both once. With Git for Windows installed, the customization catalog also offers **New Git Bash**. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Local shells run under ConPTY** — local shells and the native Mosh client now start under the Windows pseudo console instead of winpty, which no longer interferes with keys typed into other applications while korTTY runs.
- **Mosh Client finds mosh-client.exe** — the Mosh Client (native) protocol now looks for `mosh-client.exe` in the folders of the `PATH`. See [Protocols](../features/connections.md#protocols).

### Toolbar and split panes

- **Toolbar groups wrap instead of shrinking** — when the window is too narrow, whole toolbar groups move to another line, so every button stays directly clickable instead of hiding behind an overflow chevron. See [Customizing the icon toolbar](../getting-started/main-window.md#customizing-the-icon-toolbar).
- **Focus ring can be switched off** — switch off **Frame the split pane the keyboard is in** in the Window settings to hide the ring around the active split pane. You then recognize the active pane by its cursor. See [Tabs](../reference/settings/window.md#tabs).

### Snippets

- **Folder analysis by file** — the folder analysis tab lists the folder's files beside the report. Pick a file, or step through the files with ◀ ▶ and the arrow keys, to see only its findings and a short description of what the file does; each row shows how many of its findings are ticked, and its checkbox ticks or clears them all. Findings keep their file even when the model numbers them per file or names the file another way. See [Analysing a folder as one project](../features/snippets.md#analysing-a-folder-as-one-project).
- **Vertical or horizontal flow diagram** — an **Orientation** control in the diagram toolbar draws the flowchart top to bottom or left to right, in the analysis panel, its zoom window and the Diagram dialog. Switching redraws the stored diagram without asking the AI again. See [Diagram appearance](../features/snippets.md#diagram-appearance).

### AI

- **"Model default" instead of "Disabled"** — the reasoning setting that sends no reasoning parameter is now called **Model default**, because the model's own default then applies; only **None** switches reasoning off. A tooltip on the dropdown explains the difference. See [Reasoning Effort Levels](../reference/settings/ai.md#reasoning-effort-levels).
- **Connection test accepts reasoning-only replies** — **Test AI Connection** and **Refresh reasoning options** now pass when a model that reasons by default, such as Qwen in LM Studio, spends the short test reply on reasoning, and a failed refresh names the endpoint's own reason.

### Terminal

- **Clipboard protocol of kitty (OSC 5522)** — programs that speak kitty's clipboard protocol, such as Neovim and `kitten clipboard`, can copy to the clipboard under the OSC 52 setting, and with the new **Let programs in the terminal ask to read the clipboard (OSC 5522)** setting they can ask to read it. Every read asks you first, **Deny** is the default, and a paste into a program that asked for paste events counts as your consent. Only text is exchanged; the strict terminal mode drops the protocol, and the policy key `allow-osc5522-clipboard-read = false` forbids reading. See [Programs reading the clipboard](../features/terminal-notifications.md#programs-reading-the-clipboard-osc-5522).

- **Scrollback memory limit** — the new **Scrollback memory** slider in the Terminal settings sets the most memory the scrollback of one pane may use, from 1 to 50 MB (10 MB by default), next to the line count; whichever limit is reached first drops the oldest lines. A saved change applies to open terminals at once. See [Terminal settings](../reference/settings/terminal.md).
- **Scrollback compression** — while a terminal has no output, korTTY compresses the scrollback lines that are not on screen in the background, so the same memory holds several times as many lines. A line is unpacked as soon as you scroll to it. Switch it off with **Compress the scrollback in the background while idle**. See [Terminal settings](../reference/settings/terminal.md).

### Fixes

- **Dismissed sessions stay dismissed** — closing the last tab on purpose now empties the session snapshot, so the restore offer no longer comes back at every start after you discard it.
- **Steady diagram tooltips** — the code-reference tooltip in the enlarged analysis diagram stays open while the mouse rests on a node instead of flashing and closing.
- **Folder analyses survive a malformed key** — an analysis whose JSON lacks the opening quote of one key is repaired instead of being discarded as "no usable analysis".
- **German label for reasoning** — the German user interface now labels this setting of AI profiles "Reasoning", as the guide does.
- **Wide dialogs open at their size in the AtlantaFX designs** — under an AtlantaFX design, a dialog laid out wider than 600 px (such as the settings, **Compare folders** in the SFTP manager or the **Logfile Manager**) opened squeezed or grew after it appeared; it now opens as wide as in the Normal design, and a long message still wraps.
