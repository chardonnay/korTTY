# Menu reference

Every item in korTTY's menu bar, with its shortcut (where defined) and what it does. The menu bar can be hidden with ++ctrl+shift+l++ and shown again by right-clicking the terminal or status bar.

## File

| Item | Shortcut | Description |
| --- | --- | --- |
| New Tab | ++ctrl+t++ | Open Quick Connect in a new terminal tab |
| Rename Tab… | | Give the active terminal tab a name of its own instead of the connection's name or the [title its shell set](../features/terminal.md#title-from-the-shell); an empty name shows that again. Available while a terminal tab is active. See [Working with tabs](../features/terminal.md#working-with-tabs) |
| Close Tab | ++ctrl+w++ | Close the active terminal tab |
| Close Other Tabs | | Close every other tab of the current window; the active tab stays open. Asks once when some of the closing terminals have split panes or a running command. The same entry is in a terminal tab's right-click menu. See [Working with tabs](../features/terminal.md#working-with-tabs) |
| Close Tabs to the Right | | Close the tabs after the active tab, which stays open. Asks like Close Other Tabs. The same entry is in a terminal tab's right-click menu |
| Close All Tabs | | Close every tab in the current window; Reopen Closed Tab brings the terminal tabs back |
| Reopen Closed Tab | ++ctrl+alt+shift+t++ | Reopen the terminal tab you closed last (or the tabs one command closed together) with a new session; greyed out while there is nothing to reopen. The same entry is in a terminal tab's right-click menu. See [Working with tabs](../features/terminal.md#working-with-tabs) |
| Recently Closed ▸ | | What you closed in this session, newest first: a tab, the tabs one command closed together, or *Window: …* for the tabs of a closed window. Choose an entry to reopen it; **Clear List** empties the list |
| New Window | ++ctrl+shift+n++ | Open an additional, independent main window |
| Close Window | ++ctrl+shift+w++ | Close the current window |
| Open Project… | ++ctrl+o++ | Restore a saved project (windows, tabs, layout) |
| Save Project… | ++ctrl+s++ | Save the current session as a project (`.kortty`) |
| Create Backup… | ++ctrl+shift+b++ | Create an encrypted backup (ZIP password or GPG) |
| Import Backup… | | Restore from a backup file |
| Quit | ++ctrl+q++ | Exit korTTY |

Connection entries (Quick Connect, Manage/Import/Export Connections) live in the [Connections](#connections) menu.

## Edit

| Item | Shortcut | Description |
| --- | --- | --- |
| Cut | ++ctrl+x++ | Cut (disabled for terminal tabs) |
| Copy | ++ctrl+c++ | Copy the terminal selection |
| Paste | ++ctrl+v++ | Paste into the terminal |
| Find… | ++ctrl+f++ | Search the active tab (terminal scrollback or open editor). On Windows and Linux a focused terminal sends ++ctrl+f++ to the shell, so open Find from this menu or the terminal's right-click menu there |
| Quick Select | ++ctrl+shift+space++ | Label every web address, path, e-mail address, UUID, IP address, git hash and number of four or more digits in the focused terminal pane; type a label to copy its text, or ++shift++ and the label to open a web or e-mail address, or in SSH and local-shell tabs a file path in the Snippet Editor. Disabled outside terminal tabs, see [Quick select](../features/terminal.md#quick-select) |
| Previous Prompt | ++ctrl+shift+up++ | Scroll the focused terminal pane to the prompt before the one you are at, using the marks of a shell set up for [shell integration](../features/shell-integration.md); when it cannot, the status bar says why. Disabled outside terminal tabs |
| Next Prompt | ++ctrl+shift+down++ | Scroll the focused terminal pane to the next prompt its shell marked, or back to the bottom after the last one. Disabled outside terminal tabs |
| Select Last Output | | Select what the last finished command printed in the focused terminal pane, using the marks of a shell set up for [shell integration](../features/shell-integration.md#selecting-and-copying-a-commands-output); the status bar says what was selected, or why nothing was. Disabled outside terminal tabs |
| Copy Last Output | | Copy what the last finished command printed in the focused terminal pane to the clipboard, without changing the selection. Disabled outside terminal tabs |

## Connections

| Item | Description |
| --- | --- |
| Quick Connect… | Connect to a host without saving |
| Manage Connections… | Open the Connection Manager (tree, search, edit) |
| Import… | Import connections from other clients |
| Export… | Export connections |
| SFTP-Client… | Open the dual-panel SFTP file manager |

## Security

| Item | Shortcut | Description |
| --- | --- | --- |
| Unlock Vault… | | Enter the master password to unlock stored secrets when the startup prompt is off (disabled while unlocked) |
| Credentials… | ++ctrl+shift+m++ | Manage stored credentials (encrypted). The shortcut also works while a terminal has the focus |
| GPG-Keys… | ++ctrl+shift+g++ | Manage GPG keys used for backup encryption |
| SSH-Keys… | ++ctrl+shift+i++ | Manage SSH keys and passphrases |
| Known Hosts… | | Review, search and remove trusted SSH host keys |

## Configuration

| Item | Description |
| --- | --- |
| Global Settings… | Open the global Settings dialog (all tabs) |
| Prevent System Sleep | On macOS and Windows, enable activity-based system-sleep prevention. The computer is kept awake only while a terminal is connected, an enabled Scheduler job has a future run or is running, or an AI request is in progress. With none of these activities, system sleep remains available even while the item is checked. Display sleep is unaffected. On Linux the item is visible but disabled. |

See the [Settings reference](settings/index.md) for every individual setting.

## Tools

![Tools menu](../assets/screenshots/main/menu-tools.png)

| Item | Description |
| --- | --- |
| Snippet Manager… | Create, edit, organize, send and export command snippets in one workspace: the library and the snippets you edit as tabs, each with its stored Full code analysis |
| JobScheduler… | Schedule background command / snippet / AI-agent / AI-swarm / SFTP / Rsync jobs |
| Video Manager… | Manage terminal recordings and export to WebM/MKV via `ffmpeg` |
| Start/Stop Terminal Recording | Toggle recording of the active terminal (++ctrl+shift+e++) |
| Session Journals… | Manage [session journals](../features/session-journal.md): search, open, rename, describe, export and delete them, and set the journal options (++ctrl+alt+j++) |
| Start/Stop Session Journal | Toggle the session journal of the active terminal tab; starting mid-session imports the existing scrollback (++ctrl+alt+t++) |
| Add Journal Screenshot | Snapshot the active terminal into its running session journal (++ctrl+alt+c++) |
| ASCII Art… | Two tabs in one dialog: **Text Banner** renders text as a FIGlet banner in multiple font styles, **AI Picture** lets an AI profile draw a subject as ASCII art |

The three session-journal items stay visible but are disabled when an [enterprise policy](../features/session-journal.md#enterprise-policy) denies the session-journal feature.

## AI

![AI menu](../assets/screenshots/main/menu-ai.png)

| Item | Description |
| --- | --- |
| AI Manager… | Manage AI profiles, integrated GGUF models, RAG knowledge stores, the AI Skills library, and Text/Coding roles |
| Saved Chats… | Open the saved AI chat conversations directly in their own window; invoking it again brings the existing window to the front |
| AI Agent… | Open the terminal AI agent |
| AI Planning… | Open the AI planning workflow |
| AI Swarm… | Broadcast one AI task to many servers and compare the answers (++ctrl+alt+s++) |

**AI Manager** opens as a modeless window, so it can remain visible while you use the main window. Invoking it again restores and focuses the same manager for that main window instead of creating a duplicate. Its five sections are **Profiles** (connection mode, model, prompt preset, reasoning, internet access and token budget), **Local Models** (search/download/import GGUF models), **Knowledge Stores** (RAG sources), **AI Skills** (the skill library, moved here from the global settings dialog) and **Local AI** (Text/Coding/embedding roles and the local runtime). The open primary section remains marked by a bold accent underline after you move focus into its tables, fields, or buttons:

![AI Manager with Local Models selected and persistently underlined](../assets/screenshots/ai/ai-manager.png)

## Plugins

| Item | Description |
| --- | --- |
| Terminal Effects… | Enable/disable, configure, import/export terminal-effect plugins |

## View

| Item | Shortcut | Description |
| --- | --- | --- |
| Show Dashboard | ++ctrl+shift+d++ | Toggle the connections dashboard |
| Show Command Timestamps | ++ctrl+shift+t++ | Toggle inline command timestamps |
| Show Menu Bar | ++ctrl+shift+l++ | Toggle the menu bar |
| File Browser ▸ Show on Left | ++ctrl+shift+k++ | Dock the local [file browser](../features/file-browser.md) to the left of the terminal; unchecking the active side hides it |
| File Browser ▸ Show on Right | ++ctrl+shift+r++ | Dock the local [file browser](../features/file-browser.md) to the right of the terminal; unchecking the active side hides it |
| Zoom In | ++alt+plus++ | Increase terminal font size |
| Zoom Out | ++alt+minus++ | Decrease terminal font size |
| Reset Zoom | ++alt+0++ | Reset terminal font size |
| Background Transparency | | Slider (0–100 %) that makes the terminal background see-through to the desktop while the text stays sharp; every split pane inherits the value. The value is saved across restarts; fullscreen temporarily renders terminal backgrounds opaque and restores the value when you leave. Switching it on or off needs a restart, so the status bar shows a hint when you cross that threshold. Shown in the in-window menu bar only. |
| Highlighting ▸ Highlighting On | ++ctrl+shift+h++ | Switch [keyword highlighting](../features/highlighting.md) on or off for the focused pane of the active tab; switching it back on restores the set the pane showed last. Applies to the running session only |
| Highlighting ▸ None / rule set | | Choose the rule set the focused pane shows: **None**, a built-in set (**Errors and warnings**, **Network addresses**, **Network devices**) or one of your own. Applies to the running session only |
| Highlighting ▸ Manage Rule Sets… | | Open the [rule-set editor](../features/highlighting.md#your-own-rule-sets) to create, change, duplicate or delete your own rule sets and look at the built-in ones; it opens on the set the focused pane shows |
| Fullscreen | ++f12++ | Toggle window fullscreen |
| Terminal-only Fullscreen | ++ctrl+shift+f++ | Show the whole korTTY window — menus, tabs and status bar included — kept at its previous window size and centered on an empty fullscreen background, hiding the desktop and other windows |
| Hide terminal scrollbars in fullscreen | | Also hide scrollbars while in fullscreen |
| AI Agent Panel ▸ At Bottom / Dock Left / Dock Right | | Choose where the AI agent activity panel lives |
| Live Journal ▸ Dock Left / Dock Right | | Dock the [live journal panel](../features/session-journal.md#the-live-journal-panel) beside the terminal; selecting the active side hides it |
| Live Journal ▸ Show/Hide | ++ctrl+alt+l++ | Toggle the live journal panel on its last-used side (right by default) |
| Coding Agents ▸ Dock Left / Dock Right | | Dock the [Coding Agents panel](../features/coding-agents.md#the-coding-agents-panel) beside the terminal; selecting the active side hides it |
| Coding Agents ▸ Show/Hide | ++ctrl+alt+g++ | Toggle the Coding Agents panel on its last-used side (right by default) |
| Coding Agents ▸ Next Blocked Agent | ++ctrl+alt+n++ | Bring the next coding agent that is waiting for a decision to the front, across windows |

## Teamwork

| Item | Description |
| --- | --- |
| Teamwork Settings… | Configure shared connection sources and synchronization |

## Help

| Item | Shortcut | Description |
| --- | --- | --- |
| Manual | ++f1++ | Open this documentation inside korTTY |
| About korTTY | | Version and project information |

The manual has its own text-size buttons at the top left of its window: `A-`, the current percentage, and `A+`. Clicking the percentage resets it. The same three actions have keyboard shortcuts: ++cmd+plus++, ++cmd+minus++, ++cmd+0++. korTTY remembers the size, and the size covers the whole window — the page and, when it is open, the AI search panel beside it. See [Manual text size](settings/appearance.md#manual-text-size).

Screenshots and diagrams enlarge on click: the picture opens over the page at the largest size the window allows, with **−** / **+** buttons and a percentage that returns it to that fitted size. Zoom further to read a single setting row, drag the picture to pan, and close with the **×**, ++esc++ or a click next to the picture. ++ctrl++ and the wheel zoom as well. The same works in the online guide.

## macOS Dock & menu bar

On macOS the packaged app keeps running in the background (so the JobScheduler can run scheduled jobs) even after the last window is closed. korTTY therefore adds two extra entry points so it stays reachable — and quittable — with no window open:

- **Dock icon menu** — right-click korTTY's Dock icon for quick actions: **New Window**, **New Tab**, **Manage Connections…**, **Open Project…**, **Manual**, **About korTTY**, and **Quit**.
- **Menu-bar (status) icon** — a system-tray icon with **New Window** and **Quit**; clicking the icon opens a new window.

Both provide a reliable **Quit** even when every window is closed.

The menu bar at the top of the screen stays as well when you close the last window, and while no korTTY window has the focus it can still be the menu bar of a window you closed. Its entries then act in the korTTY window you used last, which comes to the front, or open a new window first when none is open: **New Tab** opens Quick Connect there, **Manage Connections...** the Connection Manager. **New Window**, **Quit**, **Prevent System Sleep** and cancelling a running job need no window. **Close Tab**, **Close All Tabs**, **Close Window**, **Cut**, **Copy** and **Paste** do nothing from the menu bar of a closed window, so they never close a tab or paste into a window you are not looking at.
