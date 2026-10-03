# Keyboard shortcuts

On macOS, use ++cmd++ where ++ctrl++ is shown.

## General

| Shortcut | Action |
| --- | --- |
| ++ctrl+t++ | New Tab (Quick Connect) |
| ++ctrl+w++ | Close Tab |
| ++ctrl+alt+shift+t++ | Reopen the last closed terminal tab (also while a terminal has the focus) |
| ++ctrl+shift+n++ | New Window |
| ++ctrl+shift+w++ | Close Window |
| ++ctrl+tab++ | Next Tab (++ctrl++ on macOS too) |
| ++ctrl+shift+tab++ | Previous Tab (++ctrl++ on macOS too) |
| ++ctrl+1++ … ++ctrl+8++ | Jump to the first to eighth tab of the window (top row or numpad) |
| ++ctrl+9++ | Jump to the last tab of the window |
| ++ctrl+o++ | Open Project |
| ++ctrl+s++ | Save Project |
| ++ctrl+shift+b++ | Create Backup |
| ++ctrl+q++ | Quit |
| ++ctrl+x++ | Cut (disabled for terminal tabs) |
| ++ctrl+c++ | Copy |
| ++ctrl+v++ | Paste |
| ++ctrl+f++ | Find in the active tab (in a focused terminal on Windows and Linux the key goes to the shell, see [Terminal](#terminal)) |
| ++ctrl+shift+space++ | Quick Select: label the web addresses, paths, e-mail addresses, IP addresses, hashes and numbers the focused terminal pane shows, to copy one or open it with a key (terminal tabs only), see [Quick select](../features/terminal.md#quick-select) |
| ++ctrl+k++ | Quick Connect |
| ++ctrl+m++ | Manage Connections |
| ++ctrl+shift+u++ | SFTP client |
| ++ctrl+shift+m++ | Manage credentials (also while a terminal has the focus) |
| ++ctrl+shift+g++ | Manage GPG keys |
| ++ctrl+shift+i++ | Manage SSH keys |
| ++ctrl+comma++ | Global Settings |
| ++ctrl+shift+s++ | Snippet Manager |
| ++ctrl+shift+j++ | JobScheduler |
| ++ctrl+shift+v++ | Video Manager |
| ++ctrl+shift+e++ | Start / stop terminal recording |
| ++ctrl+alt+j++ | Session Journals |
| ++ctrl+alt+t++ | Start / stop the session journal of the active tab |
| ++ctrl+alt+c++ | Add a screenshot to the running session journal |
| ++ctrl+alt+l++ | Show/hide the live journal panel on its last-used side |
| ++ctrl+alt+g++ | Show/hide the Coding Agents panel on its last-used side |
| ++ctrl+alt+n++ | Jump to the next coding agent waiting for a decision, across windows |
| ++ctrl+shift+a++ | ASCII Art |
| ++ctrl+shift+y++ | Open AI Manager |
| ++ctrl+alt+a++ | Open AI Agent |
| ++ctrl+alt+p++ | Open AI Planning |
| ++ctrl+alt+s++ | Open AI Swarm |
| ++ctrl+shift+d++ | Toggle Dashboard |
| ++ctrl+shift+t++ | Toggle command timestamps |
| ++ctrl+shift+l++ | Show / Hide Menu Bar |
| ++ctrl+shift+k++ | Dock the file browser on the left |
| ++ctrl+shift+r++ | Dock the file browser on the right |
| ++alt+plus++ | Zoom In |
| ++alt+minus++ | Zoom Out |
| ++alt+0++ | Reset Zoom |
| ++ctrl++ + mouse wheel | Zoom the terminal font in/out (Cmd + wheel on macOS) |
| ++ctrl+d++ | Close a local cmd.exe/PowerShell tab (EOF for bash-family shells and SSH) |
| ++f1++ | Open the manual (**Help → Manual**) |
| ++f12++ | Toggle Fullscreen |
| ++ctrl+shift+f++ | Toggle Terminal-only Fullscreen |
| ++ctrl+shift+h++ | Switch [keyword highlighting](../features/highlighting.md) on or off for the focused terminal pane |

The zoom keys (++ctrl++ or ++alt++ with ++plus++ / ++minus++ / ++0++; ++cmd++ on macOS) zoom the terminal only while a terminal tab is selected. In a snippet editor or file editor tab they reach the editor, and ++alt-graph+plus++ types its character there.

The tab jump keys work in every tab, also while a terminal or an editor has the focus, and a number with no tab at its position does nothing. On macOS they are ++cmd++ with a digit, and ++cmd+shift++ with a digit works too, so a French (AZERTY) Mac can reach its digits (macOS keeps ++cmd+shift+3++ to ++cmd+shift+5++ for screenshots). On Windows and Linux they are exactly ++ctrl++ with a digit: ++alt-graph++ combinations (which arrive as ++ctrl+alt++) and ++ctrl+shift+6++ (the Cisco break sequence) still reach the terminal. A digit key that types ++plus++ or ++minus++ in your layout, such as the AZERTY 6 key, stays a zoom key instead. On Linux with a layout whose number row types other characters, the top-row digits may not jump; the numpad digits (with ++num-lock++ on) do.

Reopen Closed Tab uses ++ctrl+alt+shift+t++ because ++ctrl+shift+t++ toggles the command timestamps and ++ctrl+alt+t++ the session journal. On Windows, ++alt-graph++ arrives as ++ctrl+alt++, so on a layout where ++alt-graph+shift+t++ types a character (such as `Þ` on US-International) that combination reopens a closed tab instead, and the character is not typed.

## Terminal

These keys work while a terminal pane has the focus. For how they behave with several panes, see [Broadcast mode](../features/terminal.md#broadcast-mode).

| Shortcut | Action |
| --- | --- |
| ++ctrl+shift+c++ / ++ctrl+shift+v++ | Copy the terminal selection / paste into the terminal (Windows and Linux; ++cmd+c++ / ++cmd+v++ on macOS) |
| ++ctrl+l++ (Windows and Linux) | Sent to the shell: redraws or clears the screen in bash, psql or a REPL, and the scrollback is kept |
| ++ctrl+f++ (Windows and Linux) | Sent to the shell: moves the cursor one character forward at a bash prompt and pages forward in `less` or `vim` |
| ++cmd+k++ (macOS) | Clear Buffer: empty the terminal screen and its scrollback |
| ++cmd+f++ (macOS) | Find in the terminal scrollback |
| ++shift+tab++ | Back-tab (`ESC [ Z`), for example to go back one field or panel in a full-screen program |
| ++ctrl+left++ / ++ctrl+right++ | Move one word left / right in the shell (Windows and Linux) |
| ++option+left++ / ++option+right++ | Move one word left / right on macOS (sends `ESC b` / `ESC f`, as Terminal.app does) |
| ++shift+page-up++ / ++shift+page-down++ | Scroll korTTY's scrollback by a page; inside a full-screen program such as `vim`, `less` or `mc` the key goes to the program |
| ++ctrl+up++ / ++ctrl+down++ | Scroll korTTY's scrollback by a line (++cmd+up++ / ++cmd+down++ on macOS); on Windows and Linux the keys go to a full-screen program instead while one runs |
| ++ctrl+tab++ / ++ctrl+shift+tab++ | Next / previous tab, also while the terminal has the focus (the ++ctrl++ key on macOS too) |
| ++ctrl+1++ … ++ctrl+9++ | Jump to a tab, also while the terminal has the focus (++cmd++ on macOS, see [General](#general)) |
| ++ctrl+alt+shift+t++ | Reopen the last closed terminal tab, also while the terminal has the focus (++cmd+option+shift+t++ on macOS) |
| ++ctrl++ + click on a link | Open the link in the browser or mail program (++cmd++ + click on macOS), also a web or e-mail address printed as plain text; a file path or `file:` link opens as text in the Snippet Editor in SSH and local-shell tabs; a plain click on a link does nothing, and right-click → **Open Link** or **Open File in Snippet Editor** works without the key, see [Links in terminal output](../features/terminal.md#links-in-terminal-output) |
| a label's letters / ++shift++ + the last letter (while quick select runs) | Copy the labelled text / open a web or e-mail address, or a file path in the Snippet Editor; ++backspace++ takes back the first letter of a two-letter label, ++esc++ or any other key ends quick select, see [Quick select](../features/terminal.md#quick-select) |

On Windows and Linux, ++ctrl+1++ to ++ctrl+9++ no longer reach the program in the terminal: korTTY's terminal never sent them as keys of their own, so no program loses a binding it could receive.

On Windows and Linux, **Clear Buffer** and **Find** have no key of their own, so ++ctrl+l++ and ++ctrl+f++ stay with the programs running in the terminal. Use right-click → **Clear Buffer** or **Find** in the terminal, or **Edit → Find…** with the mouse. ++ctrl+shift+f++ stays Terminal-only Fullscreen and ++ctrl+shift+k++ still docks the file browser on the left. ++ctrl+shift+h++ switches keyword highlighting and does not reach the terminal, while plain ++ctrl+h++ still reaches the shell as backspace.

The paste keys go through [paste protection](../features/terminal.md#paste-protection): a paste with line breaks, with control characters or of a large size can open a confirmation first. In that dialog **Cancel** is the default button, so ++enter++, ++space++ and ++esc++ drop the paste; click **Paste**, or press ++tab++ to reach it and ++space++ to press it. While a paste is sent line by line ([Pause after each pasted line](../features/terminal.md#pasting-into-slow-devices)), the pane takes no other keys and ++esc++ stops the paste.

Every other combination of ++shift++, ++ctrl++ and ++alt++ with the arrow keys, ++home++ / ++end++, ++page-up++ / ++page-down++, ++insert++ / ++delete++ and ++f1++ to ++f11++ is sent the way xterm sends it (++f12++ always toggles fullscreen, and in a tab with two or more panes ++ctrl+alt++ with an arrow key moves the focus to another pane instead, see [Panes](#panes)), for example ++ctrl+page-up++ as `ESC [ 5 ; 5 ~` and ++shift+f1++ as `ESC [ 1 ; 2 P`. The arrow keys follow the program's cursor-key mode: `mc` and `vim` switch it on and then receive `ESC O A`, while a shell receives `ESC [ A`. Connections with a non-xterm terminal emulation (Wyse, TeleVideo, HP, SCO ANSI, IBM 3270/5250, PETSCII) keep sending fixed sequences without modifiers.

## Panes

These keys move the keyboard focus between the split panes of the active terminal tab; see [Split operations](../features/terminal.md#split-operations).

| Shortcut | Action |
| --- | --- |
| ++ctrl+alt+left++ / ++ctrl+alt+right++ | Move the focus to the pane on the left / right (++cmd+option+left++ / ++cmd+option+right++ on macOS) |
| ++ctrl+alt+up++ / ++ctrl+alt+down++ | Move the focus to the pane above / below (++cmd+option+up++ / ++cmd+option+down++ on macOS) |

The keys act only while a terminal tab with two or more panes is active and the keyboard is in that tab; with a single pane they reach the program in the terminal as before. At the edge of the tab the focus stays where it is. When several panes lie on that side, the focus goes to the one that lies beside the focused pane rather than only touching its corner, and among those to the nearest one. *View → Panes* has the same commands, and **Next Pane** and **Previous Pane**, which go through all panes of the tab and start over after the last one.

!!! note "A desktop shortcut can take these keys first"
    Some Linux desktops, such as GNOME, Xfce and Cinnamon, switch workspaces with ++ctrl+alt++ and an arrow key, and some Windows graphics drivers (Intel) rotate the screen with it, before korTTY sees the key. Use *View → Panes* there, or switch off the desktop's shortcut.

## SFTP Manager

These keys work in the local and the remote list of an SFTP Manager tab; see [Keys](../features/sftp.md#keys).

| Shortcut | Action |
| --- | --- |
| ++f2++ | Rename the selected entry |
| ++delete++ or ++ctrl+backspace++ | Delete the selection, after confirmation |

## Snippet Manager

These keys work in the Snippet Manager (library and editor tabs); see [Opening the Snippet Manager](../features/snippets.md#opening-the-snippet-manager).

| Shortcut | Action |
| --- | --- |
| ++ctrl+shift+s++ | Open the Snippet Manager, or focus its search field when it is already open |
| ++up++ / ++down++ (list) | Browse: show the selected snippet in the read-only preview tab |
| ++enter++ (list) | Open the selected snippet in an editor tab |
| Typing (preview tab) | Open the previewed snippet in an editor tab; the typed character is kept |
| ++ctrl+s++ | Save the active editor tab (also in a standalone snippet editor window) |
| ++ctrl+w++ | Close the active editor tab (asks about unsaved changes) |
| ++ctrl+b++ | Hide or show the library, giving the editor tabs the full width |
| ++ctrl+p++ | Quick open: find a snippet by part of its name or a tag and open it in an editor tab (++up++ / ++down++ choose, ++enter++ opens, ++esc++ closes) |
| ++esc++ (search field) | Clear the search; ++esc++ never closes the Snippet Manager |
| ++down++ (search field) | Move to the snippet list (selects the first snippet when none is selected) |

If the focus is on the Snippet Manager's own tab header in the main window (tab mode), ++ctrl+s++ still saves the project, and ++ctrl+w++ closes the Snippet Manager tab after asking about unsaved changes.

## Snippet editor

These keys work inside the snippet editor's code field; see [AI Code completions](../features/snippets.md#ai-code-completions).

| Shortcut | Action |
| --- | --- |
| ++shift+tab++ (cursor at the end of a line with text) | Open the completion list (the combination is configurable, see [Snippet Editor settings](settings/snippet-editor/index.md)) |
| ++ctrl+space++ | Open the completion list anywhere (on macOS the physical ++ctrl++ key, not ++cmd++; ++cmd+i++ and ++alt+esc++ work there too, because macOS often reserves ++ctrl+space++ for switching input sources) |
| ++up++ / ++down++ (list open) | Move the selection; typing filters the list |
| ++tab++ or ++enter++ (list open) | Insert the selected entry |
| ++esc++ | Close the list, or dismiss ghost text |
| ++esc++ (an AI request runs) | Stop the running AI request of this editor — an AI Code action, the Full code analysis or its apply, a diagram; works in the whole editor and its analysis panel, never closes the editor (see [Stopping and retrying AI requests](../features/snippets.md#stopping-and-retrying-ai-requests)) |
| ++enter++ (New analysis area of the analysis panel) | Start the Full code analysis with the chosen AI profile (see [Choosing the AI profile before the analysis starts](../features/snippets.md#choosing-the-ai-profile-before-the-analysis-starts)) |
| ++tab++ (ghost text visible) | Accept the ghost text |
| ++alt+bracket-right++ / ++alt+bracket-left++ (ghost text visible) | Next / previous ghost-text candidate (physical `]` and `[` keys of a US layout) |
| ++tab++ (inside an inserted idiom template) | Jump to the next placeholder |
| ++shift+tab++ (anywhere else, or when another combination is configured) | Outdent the line, as before |
| ++ctrl+enter++ (change review shown) | **Accept & apply** the reviewed AI change (see [Reviewing an AI change](../features/snippets.md#reviewing-an-ai-change)); ++esc++ does nothing there |
| ++ctrl+plus++ / ++ctrl+minus++ (change review shown) | Zoom the review's code font |

## Diagram zoom window

These keys work in the zoom window of the Full code analysis flow diagram; see [Full code analysis](../features/snippets.md#full-code-analysis).

| Shortcut | Action |
| --- | --- |
| ++ctrl+plus++ / ++ctrl+minus++ | Zoom in / out |
| ++ctrl+0++ | Fit the diagram into the window |
| ++ctrl+1++ | Show the diagram at 100 % |
| ++ctrl++ + mouse wheel | Zoom (++cmd++ + wheel on macOS) |

## Terminal AI agent

| Shortcut | Action |
| --- | --- |
| `agent` + ++tab++ (at shell prompt) | Show agent command variants (`agent`, `agent-ask`, `agent-plan`) |
| `agent ` + ++tab++ (at shell prompt) | Show recent agent-prompt history (newest first) |
| ++esc++ or ++ctrl+c++ (during a run) | Cancel the selected agent run's tab |
| ++ctrl+r++ (during a run) | Toggle thinking details for the selected run |
| ⏸ (activity panel) | Pause the selected run at a safe checkpoint |
| ▶️ (activity panel) | Resume a paused run |

!!! note
    The complete, always-current shortcut list is generated from the application's accelerator definitions. If a shortcut here differs from what the app shows, the app is authoritative.
