# Keyboard shortcuts

On macOS, use ++cmd++ where ++ctrl++ is shown.

## General

| Shortcut | Action |
| --- | --- |
| ++ctrl+t++ | New Tab (Quick Connect) |
| ++ctrl+w++ | Close Tab |
| ++ctrl+shift+n++ | New Window |
| ++ctrl+shift+w++ | Close Window |
| ++ctrl+tab++ | Next Tab |
| ++ctrl+shift+tab++ | Previous Tab |
| ++ctrl+o++ | Open Project |
| ++ctrl+s++ | Save Project |
| ++ctrl+shift+b++ | Create Backup |
| ++ctrl+q++ | Quit |
| ++ctrl+x++ | Cut (disabled for terminal tabs) |
| ++ctrl+c++ | Copy |
| ++ctrl+v++ | Paste |
| ++ctrl+f++ | Find in the active tab |
| ++ctrl+k++ | Quick Connect |
| ++ctrl+m++ | Manage Connections |
| ++ctrl+shift+u++ | SFTP client |
| ++ctrl+shift+p++ | Manage credentials |
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

The zoom keys (++ctrl++ or ++alt++ with ++plus++ / ++minus++ / ++0++; ++cmd++ on macOS) zoom the terminal only while a terminal tab is selected. In a snippet editor or file editor tab they reach the editor, and ++alt-graph+plus++ types its character there.

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
