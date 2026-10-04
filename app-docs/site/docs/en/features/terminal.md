---
title: Terminal sessions
---

# Terminal sessions

KorTTY provides a tabbed terminal interface with support for multiple simultaneous SSH connections, split-screen layouts, and interactive terminal management features. This guide covers tab operations, multi-window support, terminal customization, and advanced session features.

## Session lifecycle

The following diagram shows the terminal tab and session lifecycle, including split-screen and broadcast modes.

![Terminal session lifecycle](../assets/diagrams/session-lifecycle.svg)

## Working with tabs

Manage multiple SSH sessions with these tab operations:

| Action | Shortcut |
|--------|----------|
| **New Tab** | ++ctrl+t++ (Cmd+T on macOS) — opens Quick Connect to start a new session |
| **Rename Tab** | Right-click a tab and choose **Rename Tab…**, or use *File → Rename Tab…* for the active tab. The name you enter replaces the connection's name, or the [title the shell set](#title-from-the-shell), in the tab title; the coding-agent glyph, the `[group]` prefix and the `(DISCONNECT)` suffix stay around it. Leave the name empty, or confirm the name the tab shows on its own, and the tab follows the shell's title and the connection's name again, including later changes to them. While the shell names the tab, entering the connection's name keeps that name in place. A name can be up to 120 characters long; line breaks become spaces, and control characters and the invisible bidi controls that make text read in a different order are removed. The name is saved with a [project](projects.md#what-gets-saved), and the [Coding Agents](coding-agents.md) panel and the Control API's [`tab.list`](../reference/control-api.md#what-the-methods-do) show it; **Duplicate** opens the copy under the connection's name. There is no shortcut, so every key stays with the program in the terminal. |
| **Title from the shell** | A terminal tab shows the title its shell sets, such as `user@host: directory`, in place of the connection's name, unless you renamed the tab; pointing at it shows the connection it belongs to. See [Title from the shell](#title-from-the-shell) below; it can be switched off in the [Window settings](../reference/settings/window.md#tabs). |
| **Close Tab** | ++ctrl+w++ (Cmd+W on macOS) — closes the active tab. You are only asked to confirm when there is something to lose: the tab has split panes, or a command is still running (a local shell with a running child process, or an SSH session that is not at its prompt). An idle single terminal closes immediately. The tab's close button and **Close** in the Dashboard ask the same question. The per-connection *Close tab without confirmation* setting suppresses the prompt entirely. |
| **Close Other Tabs / Close Tabs to the Right** | Right-click a terminal tab and choose **Close Other Tabs** or **Close Tabs to the Right**, or use the *File* menu entries of the same name, which act around the active tab of any kind. **Close Other Tabs** closes every other tab of the window, **Close Tabs to the Right** the tabs after it; the tab you chose stays open and becomes the active tab. An entry is greyed out while there is nothing to close. Instead of one question per tab you are asked at most once, *Close N tabs?*, with the number of them that have split panes or a command that is still running. Closing only idle terminals and other tabs asks nothing, just like their close buttons, and the *Close active terminal windows without confirmation* setting on the [Terminal settings](../reference/settings/terminal.md) tab silences the question as it does for **Close All Tabs**. A file or snippet editor with unsaved changes still asks on its own (Save / Discard / Cancel), and a Cancel at any question keeps every tab open. A running AI chat request or swarm in a closing tab stops, as with its close button. There is no shortcut. |
| **Reopen Closed Tab** | ++ctrl+alt+shift+t++ (Cmd+Option+Shift+T on macOS), *File → Reopen Closed Tab* or **Reopen Closed Tab** in a terminal tab's right-click menu brings back the terminal tab you closed last, with a new session to the same connection: its tab group, the name you gave it and its terminal effect come back, its output and its split panes do not. *File → Recently Closed* lists what you closed, newest first, also before korTTY last quit; choose an entry to reopen it, or **Clear List** to empty the list. Each close is one entry: a single tab (its close button, **Close Tab** or **Close** in the Dashboard); all the terminal tabs that one **Close Other Tabs**, **Close Tabs to the Right** or **Close All Tabs** closed, which reopen together; or the terminal tabs of a window you closed while korTTY kept running, shown as *Window: …*, which reopen in a new window, or in the current one when it has no tabs. On macOS, where korTTY keeps running after you close its last window, *File → Reopen Closed Tab* and *File → Recently Closed* in the menu bar open a new window for what they reopen. Tabs whose session ended on their own (`exit`, ++ctrl+d++), the tabs that opening a [project](projects.md) replaces and everything that closes when you quit korTTY are not listed; what was open when you quit comes back with [*File → Restore Previous Session*](projects.md#previous-session). The list holds the last 25 closes for all windows together and survives a restart: korTTY keeps it in its [session snapshot](projects.md#previous-session) with only the id of each tab's saved connection, so after a restart a Quick Connect session that was never saved, or a tab whose connection you deleted, is no longer listed. A reopened tab signs in like **Connect** in the Connection Manager (see [Signing in](connections.md#signing-in)) and uses the saved connection with the changes you made since, so the [server access policy](../reference/enterprise-policy.md#server-access-control) is checked again; a login method you chose for that one session in Quick Connect is not repeated. A Quick Connect session that was never saved reopens with the settings it had, until korTTY quits. The list never keeps a temporary SSH key, so a session that used one asks for a new key. If you cancel a password, key or vault question, the entry stays in the list. The key works in every tab, also while a terminal has the focus and with the menu bar hidden; when there is nothing to reopen, the status bar says so. |
| **Bell mark** | When a program rings the terminal bell or asks for a notification in a tab you are not looking at, the tab shows 🔔 in its title until you select it or bring its window to the front, and a desktop notification can show as well. See [Terminal notifications](terminal-notifications.md). |
| **Monitor for Activity / Silence** | Right-click a terminal tab and choose **Monitor for Activity** to be told when output appears in it after 10 seconds of quiet, or **Monitor for Silence** to be told when a pane that was printing stays silent, 30 seconds by default. The tab gets 🔔 and a desktop notification while you are not looking at it. Both are off until you switch them on and are never saved. See [Watching a tab for activity or silence](terminal-notifications.md#watching-a-tab-for-activity-or-silence). |
| **Next Tab** | ++ctrl+Tab++ (++ctrl++ on macOS too). With **Ctrl+Tab switches tabs in the order they were last used** on in the [Window settings](../reference/settings/window.md#tabs), it goes back to the tab you used before the current one instead; keep ++ctrl++ held and press ++tab++ again to go further back, and release ++ctrl++ at the tab you want. |
| **Previous Tab** | ++ctrl+shift+Tab++, or with the same setting on, the tab you used longest ago, stepping towards the recent ones while ++ctrl++ stays held |
| **Jump to a tab** | ++ctrl+1++ to ++ctrl+8++ (++cmd++ on macOS) select the first to eighth tab of the window and ++ctrl+9++ the last one, with the digits of the top row or the numpad. The keys work in every tab, also while the terminal has the focus, and a number with no tab at its position does nothing. No digit is typed into the terminal, nor in [broadcast mode](#broadcast-mode) into the other panes. On Windows and Linux only ++ctrl++ with a digit jumps, so ++alt-graph++ characters and ++ctrl+shift+6++ still reach the terminal; see [Keyboard shortcuts](../reference/keyboard-shortcuts.md#general) for the layout details. |
| **Reconnect** | Right-click a tab, the terminal area, or a server entry in the Dashboard. If the connection is active, it is closed and re-established immediately; if disconnected, it is re-established. The terminal window stays open. |
| **Tab Groups** | Right-click a tab to assign it to a named group for better organization; **No Group** takes it out of its group again. The same menu offers **Duplicate**, which opens another tab to the same connection and signs in like the Connection Manager (see [Signing in](connections.md#signing-in)). |
| **Tab color** | A connection with a [tab color](connections.md#tab-color) marks each of its terminal tabs with a colored dot in front of the title and a 3-pixel frame of that color around the terminal, for example red for production servers. The frame surrounds all split panes of the tab and can be switched off in the [Window settings](../reference/settings/window.md#tabs); turning it on or off resizes the terminal by 3 pixels on each side. A split pane opened to a connection with a different color gets a frame of its own color at its edge, which the tooltip lists (see [Split panes of other connections](connections.md#tab-color)). Pointing at such a tab shows a tooltip with the connection and the color by name, which screen readers read for the dot as well. Set the color in the connection editor on the *Terminal Settings* tab; saving it in the Connection Manager updates the open tabs in every window. A connection without a color of its own can show the color of its Connection Manager [folder](connections.md#tab-color) or of its stored credential's [environment](security.md#environments-and-tab-colors) instead. The yellow tab of a connection being made and the dark red tab of a failed or lost connection are unchanged. |

### Title from the shell

Many shells and programs set a terminal title with the OSC 0 or OSC 2 escape sequence: the default `bash` of Debian and Ubuntu puts `user@host: directory` there, and editors or coding agents put the file or the task. A terminal tab shows that title in place of the connection's name, with the coding-agent glyph, the `[group]` prefix and the `(DISCONNECT)` suffix around it as usual. A tab with split panes shows the title of the pane that has the focus, and switches when you move to another pane.

- A name you gave the tab with **Rename Tab** always comes first. Clear that name again and the tab follows the shell's title once more.
- Pointing at a tab named by its shell shows a tooltip with the connection it belongs to: `user@host` and the connection's name.
- The server decides this title, so korTTY removes control characters and the invisible bidi controls, caps it at 80 characters and never lets it change the tab color, the colored dot or the frame. Treat the title as a hint; the tooltip and the tab color tell you which connection it is.
- The title is not saved with a [project](projects.md) and not kept by **Reopen Closed Tab**. A reconnect drops it until the new session sets one, and a program that restores the title it found on exit, as `vim` does, brings back the connection's name when there was none before.
- The [Coding Agents](coding-agents.md) panel and the Control API's [`tab.list`](../reference/control-api.md#what-the-methods-do) report the title the tab shows.
- On Windows, the console behind a local shell can set a title of its own, such as the path of `cmd.exe`, as it does in Windows Terminal; switch the setting below off to keep the connection's name.

To keep the connection's names on every tab, switch off **Name terminal tabs after the title the shell sets** in the [Window settings](../reference/settings/window.md#tabs); the open tabs of every window follow as soon as you save.

## Connecting safely

Interactive SSH terminals share host-key trust with SFTP and the SSH bootstrap used by Mosh. The first connection to a normalized host and port shows the key algorithm and OpenSSH SHA-256 fingerprint with **No** selected by default. After you verify and accept it, exact matches connect silently; a changed key is hard-blocked with no automatic retry, and the alert offers **Review and Replace…** for a key you have verified with the server administrator. See [SSH host-key verification](connections.md#ssh-host-key-verification).

Opening a same-server or newly selected connection in a split shows a progress dialog while the SSH handshake runs on a worker. The interface remains responsive for both the host-key confirmation and keyboard-interactive authentication prompts.

**Split Right (new connection)** and **Split Down (new connection)** follow your organization's [server access policy](../reference/enterprise-policy.md#server-access-control) like Quick Connect: if the server you pick or its jump server is blocked, korTTY shows the policy message right away, before it asks for a password or saves the connection, and opens no pane. **Split Right (same server)** and **Split Down (same server)** check the policy again for the server of the pane you split: a pane opened from a saved connection can pick up later edits of it, so if its server or jump server was edited to a blocked one after the pane opened, the split shows the same message and opens no pane.

Some failures are refused outright rather than retried, because repeating the attempt cannot change the outcome — a changed host key, an SSH key file that is missing or cannot be read, a jump server whose stored password cannot be used (for example while the vault is locked) or whose setup is incomplete, a Mosh connection configured with a jump server, or a missing Mosh runtime. The terminal clears and shows the reason immediately instead of working through the retry count. See [Jump server](jump-server.md#when-the-jump-server-cannot-be-used) for the jump-server cases and the Mosh restriction.

KorTTY's pinned SithTermFX build also includes a reviewed bottom-row boundary fix: moving over a hyperlink or the final visible terminal row no longer asks `TerminalTextBuffer` for the non-existent row at `line == height`.

## Connection loss and automatic reconnect

When an **established** SSH connection is lost — network drop, VPN cut, server gone — the tab does **not** close. It switches to a red disconnected state instead: the tab title gets a `(DISCONNECT)` suffix, the tab turns dark red, a red status bar shows the time the connection was lost, and the terminal cursor stops blinking so a dead session no longer looks alive. Only a normal remote logout (typing `exit`, or ++ctrl+d++ at the prompt) closes the tab.

KorTTY notices a silent transport death within about ten seconds: every few seconds it sends an SSH liveness probe (a global request the server must answer, the same technique as OpenSSH's `ServerAliveInterval`) and treats two consecutive unanswered probes as a lost connection. The probe only arms itself after the server has answered once, so servers that never reply to such requests keep their sessions untouched. An unanswered probe only counts while nothing at all arrives from the server: when a large output, a paste or a file transfer on the same session keeps data flowing over a slow link, the reply may simply be queued behind that data, so the session stays open. A dead network delivers nothing, so it is still noticed within about ten seconds. This is independent of the [SSH keep-alive](#ssh-keep-alive) heartbeat, which keeps idle connections open but does not detect a dead one.

To pick the session back up in the same tab, double-click the red status bar or the red tab, or use **Reconnect** in the tab, terminal, or Dashboard context menu or in the [command palette](command-palette.md#terminal-and-tab-commands). In a split tab, panes whose connection died close individually; the last remaining pane keeps the tab open and carries the reconnect offer.

With **Automatically reconnect lost connections** enabled (**Settings → Terminal**, on by default), the tab reconnects on its own: attempts start after 3 seconds and back off through 5, 10, 20 and 30 seconds up to one attempt per minute, and the red status bar counts down to the next attempt. A successful reconnect, a manual reconnect, or closing the tab ends the automatic attempts. Permanent failures — authentication, host-key verification, configuration refusals — stop them too, so a wrong password is never hammered against the server. While a [session journal](session-journal.md) is running, its red decision bar takes precedence and no automatic attempt starts — the journal asks whether to reconnect and continue or to end with its closing summary. See [Settings → Terminal](../reference/settings/terminal.md) for the setting.

## Multi-window support

Open additional windows to organize connections by project or environment:

- **New Window**: ++ctrl+shift+n++ (Cmd+Shift+N on macOS) opens a new KorTTY window. Each window can have its own set of tabs and connections.
- **Move tabs between windows**: Drag a tab from the tab bar and drop it onto another KorTTY window's tab bar to move that tab (and its session, including any split terminals) into the other window.
- **Reorder tabs**: Drag a tab within the same window to change its order; the "+" tab stays at the end.
- **Save and reopen windows**: *File → Save Project* keeps every open window with its position, size and tabs, and *File → Open Project* opens them again in as many windows, see [Windows](projects.md#windows).

## Terminal context menu

Right-click inside a terminal to open its context menu; in a split tab it acts on the pane you clicked. The menu starts with the editing commands below; when you right-click a link, **Open Link** and **Copy Link Address** come before them, and on a link to a file **Open File in Snippet Editor** and **Copy Path** (see [Links in terminal output](#links-in-terminal-output)):

| Entry | What it does |
|-------|--------------|
| **Copy** | Copies the selected text to the clipboard and keeps the selection. Greyed out while nothing is selected. |
| **Paste** | Sends the clipboard text to the session, the same way the paste shortcut does (see [Pasting text](#pasting-text)). |
| **Clear Buffer** | Clears the scrollback and the screen but keeps the prompt line. While a full-screen program such as `vim` or `less` is running, it does nothing. On Windows and Linux, where it has no key, the [command palette](command-palette.md#terminal-and-tab-commands) runs it from the keyboard for the focused pane. |
| **Find** | Opens the find bar at the top right of the pane, the same as **Edit → Find...** (++ctrl+f++, ++cmd+f++ on macOS). Type to highlight matches, press ++enter++ or ++down++ for the next match and ++up++ for the previous one, and ++esc++ to close the bar. |

Below them come the entries of other features, in this order and some only where they apply: **Show Menu Bar** (while the menu bar is hidden), **Open in Snippet Editor**, the **AI** submenu, the session-journal screenshot and note entries, **Previous Prompt**, **Next Prompt**, **Select Last Output** and **Copy Last Output** or **Set Up Shell Integration…** (see [Shell integration](shell-integration.md)), **Theme**, **Highlighting** (see [Keyword highlighting](highlighting.md)), **Terminal Effect**, **Reconnect** and **Show Command Timestamps**. The **Extras** submenu at the end holds **Split Terminal**, **Font Size** (see [Font size and zoom](#font-size-and-zoom)) and **Broadcast Mode**, followed, while broadcast mode leaves panes out, by how many it leaves out (see [Broadcast mode](#broadcast-mode)), and **Multi-exec: Include This Pane** (see [Multi-exec](#multi-exec)).

## Pasting text

Every way of pasting into a terminal goes through korTTY: *Edit → Paste*, ++cmd+v++ on macOS, ++ctrl+shift+v++ on Windows and Linux, **Paste** in the right-click menu, a middle-click, which pastes the X11 primary selection on Linux and the clipboard elsewhere, and text you [drop onto a pane](#dropping-text). The text goes to the pane you paste into, and its line breaks arrive as Enter, the way they do when you type them.

When the program in the pane has switched on bracketed paste, as bash, zsh, fish and most editors do while they wait for input, korTTY wraps the text in the bracketed-paste markers. The program then receives it as one pasted block instead of typed keys, so a line break in it does not run a command on its own. Bracketed-paste markers inside the text itself are removed first, including the 8-bit form that a single-byte [character encoding](connections.md#character-encoding) sends, so text copied from a web page or a file can neither end the paste early nor have its remaining lines run as typed commands.

After a reconnect, and after a terminal reset (the `reset` command, or `ESC c` in the output), a pane does not use bracketed paste until its program switches it on again, so pasted line breaks act as Enter until then. A Mosh connection that recovers from a network interruption continues the same session and keeps the state.

### Paste protection

Some pastes ask before they reach the pane. korTTY then shows what you are about to paste, and nothing is sent until you choose **Paste**. Which pastes ask is set under *Settings → Terminal → Paste protection* (see [Terminal settings](../reference/settings/terminal.md)):

- **Line breaks**: by default a paste with a line break asks unless the program in the pane uses bracketed paste, because each line break would then act as Enter and run a command. A single line that ends in a line break asks as well, since it would run at once. With **Always**, every paste with a line break asks, bracketed or not; with **Off**, none does.
- **Control characters**: a paste that contains control characters, such as Escape, Ctrl+C or Ctrl+Z, or invisible characters that change the text direction asks whenever the warning is not **Off**, even when the program uses bracketed paste. The terminal on the server acts on Ctrl+C, Ctrl+Z and Ctrl+S before the program sees the paste, and direction-changing characters make text look different from what is sent. Ordinary text never contains them.
- **Size**: a paste larger than 5 KiB asks, whatever the warning setting, because a large paste can flood a slow program or device and is hard to check. Set the size to 0 to turn this check off.

A connection can choose its own line-break warning in the connection editor, for example **Always** for production servers while your other connections keep the default; each pane follows the connection it runs (see [Paste protection per connection](connections.md#paste-protection)).

Your organization can set the least the warning asks with the `paste-warning` key of its [enterprise policy](../reference/enterprise-policy.md#ruleterminal). The dropdown in *Settings → Terminal* then carries the "Managed by your organization" hint and offers only that level and stricter ones, and neither *Settings → Terminal*, a connection's own warning nor a teamwork connection can ask less often; a choice that asks more often stays.

![Paste confirmation](../assets/screenshots/main/paste-confirmation.png)

The dialog names the pane by the connection it runs, which for a pane opened with **Split Right (new connection)** or **Split Down (new connection)** can be another server than the tab's, and shows the number of lines and the size, lists why it asks, and says whether the program in the pane uses bracketed paste. Its preview shows the start of the text, with control characters as symbols (such as ␛ for Escape) and invisible characters as `<U+XXXX>`. It also says when the text comes from the middle-click selection, when bracketed-paste markers in it are removed, and when broadcast mode is on: a paste always goes only to the pane you paste into. **Copy** in the preview's right-click menu follows the enterprise policy's [internal clipboard mode](../reference/enterprise-policy.md#internal-clipboard-mode). When the pane's connection sets its own warning and that warning is why the dialog asks, the dialog says so and points to the connection editor instead of *Settings → Terminal*; the size check is always changed in *Settings → Terminal*.

**Cancel** is the default button and has the focus, so ++enter++, ++space++ and ++esc++ all drop the paste and typing ahead can never confirm it. Click **Paste** to paste, or press ++tab++ to reach it and ++space++ to press it. A second paste into the same pane while the dialog is open is ignored, and a paste you confirm after the pane has reconnected is dropped instead of reaching the new session.

!!! warning "Bracketed paste is what the server says"
    Whether the program uses bracketed paste is what the program on the server tells the terminal, and any output can claim it: a crafted file you `cat`, or a login message, can switch it on in a shell that does not handle it, such as `sh` or the console of many network devices. Pasted line breaks then run as commands without a warning. If you work on production servers, choose **Always**, here for every connection or in the [connection editor](connections.md#paste-protection) for those servers only.

### Pasting into slow devices

Switches, routers, console servers and other devices behind SSH can lose input that arrives faster than they read it, so a pasted configuration arrives with characters or whole lines missing. Set **Pause after each pasted line** under *Settings → Terminal → Paste protection* (0 to 1,000 ms; 0, the default, turns it off) and korTTY sends a paste with several lines one line at a time, with that pause after each line. A single line still goes out at once, and a paste that asks for confirmation is paced after you choose **Paste**. A connection can set a pause of its own, or none, in the [connection editor](connections.md#paste-protection), so only the console server's panes are paced.

While the lines are sent, the bottom right corner of the pane shows how far the paste is, such as *Pasting line 3 of 40 · Esc stops*. The pane takes no keyboard input meanwhile, so nothing you type lands between two pasted lines; on macOS, ++cmd++ shortcuts keep working, and the pane's find bar still takes what you type. Press ++esc++ to stop the paste: the remaining lines are not sent. When the program in the pane uses bracketed paste, the paced lines still arrive as one pasted block, and stopping ends that block, so the lines already sent stay in the program's input line without running.

A new paste into the pane is ignored until the paced one is done, and in [broadcast mode](#broadcast-mode) the keys you type in the other panes are not sent into it. Closing the pane, a reconnect and closing the tab stop the paste.

### Dropping text

Drag text from another application, such as a browser or an editor, or from another korTTY window, and drop it onto a terminal pane to paste it there. It goes into the pane under the pointer, which need not be the pane you were typing in, and that pane becomes the focused pane. Dropped text is a paste like any other: bracketed-paste markers in it are removed, [paste protection](#paste-protection) asks first when its rules say so, with a note in the dialog that the text was dropped onto the terminal, and a line delay paces it. The text is always copied, so the application you drag it from keeps it. A pane that is not connected, or is still pacing a paste, does not take the drop.

Dropped files are copied to the server over SFTP instead, in SSH tabs. A drag that carries files together with their path as text, as one from Finder or Explorer does, copies the files and never pastes the path. Both need **Allow drag and drop into the terminal** under *Settings → Terminal* (on by default); with it off, the terminal takes neither files nor text. With the enterprise policy's [internal clipboard mode](../reference/enterprise-policy.md#internal-clipboard-mode), text dragged from another application is refused, as a paste from the operating system clipboard is, while text dragged within korTTY is still pasted. When your organization turns file transfer off with the [enterprise policy](../reference/enterprise-policy.md#rulefeatures) (`file-transfer = "deny"`), a terminal pane does not accept dropped files: the pointer shows no copy symbol while you drag, and nothing is copied.

Dropped files go to the server of the pane under the pointer, which becomes the focused pane, into the directory its shell is in. A file inside a dropped folder that is a link is copied with the content it points to; a link to a folder is skipped, so a link that points back up the tree cannot make the copy run forever, and the progress window says how many linked folders were left out. When the pane seems to run as another user or host, for example after `su`, `sudo -i` or a nested `ssh`, korTTY does not copy the files and says why: the files would still be written with the login the tab was opened with, into a directory that may belong to someone else. Leave the inner shell with `exit` and drop them again. A drop onto a tab whose SSH session has just closed reports that the session is closed.

korTTY also notices another host from the directory reports a shell sends with OSC 7, which carry the host name. The first host reported while the session is still the one you logged in to becomes the session's own host; `localhost`, an empty host and the full name of that host (such as `web01.example.com` for `web01`) count as the same. When a report names a different host, korTTY keeps the directory it had and treats the pane as running elsewhere until the original host reports again or the tab reconnects; a prompt that merely looks like your own login does not end this.

## Links in terminal output

Programs can print clickable links into the terminal with the OSC 8 escape sequence; GCC, for example, can link a warning to its documentation. To open such a link, hold ++cmd++ (macOS) or ++ctrl++ (Windows and Linux) and click it: it opens in your default browser, or a `mailto` link in your mail program. Only `http`, `https`, `ftp`, `ftps` and `mailto` links open in the browser or mail program (`file:` links open as text in the Snippet Editor, see below), and a `mailto` link may only fill in recipients (`to`, `cc`, `bcc`), `subject`, `body` and `in-reply-to`.

The same click opens a web or e-mail address that a program prints as plain text, such as the address in a log line, a compiler message or a `git push` hint: a web address opens in your default browser, and an e-mail address such as `ops@example.com` opens as a new mail to it. A full stop, comma, quote or closing bracket right after an address is not part of it, and an address that wraps onto the next rows is found from any of its rows. KorTTY looks for the address only when you click, in the text the terminal shows at that moment, so an address that a program printed in several pieces opens whole, and printing output does not get any slower. It reads at most 16 rows above and below the click (fewer in a very wide terminal) and leaves an address that runs on beyond them alone rather than opening it cut short. What the paragraphs below say about clicking a link and about links that never open applies to these addresses too. To turn this off, clear **Detect web addresses, e-mail addresses and file paths in terminal text** in [Settings → Terminal → Links](../reference/settings/terminal.md#notes); links marked up with OSC 8 keep working.

In SSH and local-shell tabs the same click opens a file path that a program prints, such as `/var/log/syslog`, `~/notes.txt`, `./build.gradle.kts` or the `src/main/App.java:42:7` of a compiler message, as text in the [Snippet Editor](snippets.md). A relative path needs a `/` and a file extension or a line number, so words such as `and/or` or `24/7` stay plain text, and a line and column after the path (`:42`, `:42:7`, `(42,7)`) are not part of the file name. KorTTY reads the file on the machine the session runs on: over SFTP in an SSH tab, from your disk in a local-shell tab. A relative path is resolved against the shell's current directory at the moment you click (`~` against your home directory), so after a `cd` the same text can name another file; the Snippet Editor shows the full path it opened. Only a regular UTF-8 text file of at most 10 MB opens; for a directory, a missing file, a binary file or a larger one KorTTY says why instead. Network paths such as `\\server\share\x` or `//server/share/x`, device paths and another user's home directory (`~user/...`) never open, so a click cannot make Windows sign in to a server named in the output. Path links follow **Open in Snippet Editor**: the [enterprise policy](../reference/enterprise-policy.md) that removes that entry turns them off too, its read-only mode keeps the file from being written back to the server, and after `su` or an inner `ssh` KorTTY refuses to open the file instead of reading it as the wrong user. Mosh tabs have no file links.

OSC 8 `file:` links, such as the ones `ls --hyperlink`, `eza --hyperlink` or `rg --hyperlink-format=default` put on file names, open the same way in SSH and local-shell tabs, but read-only: the program that printed the link chose the file behind its text, so the Snippet Editor keeps **Overwrite** and **Save as...** locked and only lets you save the text as a snippet. Such a link opens only when it names no host, `localhost`, or the host the session runs on: the host the tab connected to, the host its prompt shows, or for a local shell the name of this computer (a short name such as `web01` matches `web01.example.com`). A link to another host, to a network share (`file:////server/share/...`) or with control characters in its path does not open, and in Mosh tabs every `file:` link stays plain text.

A link opens only on a single ++cmd++ / ++ctrl++ click that does not move the mouse between pressing and releasing the button. A plain click on a link does nothing, so a click that only puts the focus into a pane never opens a page. A double-click on a link selects its word and a triple-click its line, as on other text, and neither a ++cmd++ / ++ctrl++ double-click nor a selection you drag across the screen opens the link it ends on. ++ctrl+alt++ and a click does not open a link either, because Windows reports ++alt-graph++ as ++ctrl+alt++. The modifier click also works while a program such as tmux or vim uses the mouse.

Every other link stays plain text and does nothing when clicked: `news:`, `javascript:` and `data:` links, `mailto` links with any other field (some mail programs attach the local file an `attach` field names), and links that contain spaces, control characters or invisible direction-changing (bidi) characters or are longer than 8 KB. Whatever prints a link chooses where it points — a server, a log file you `cat`, a program's output — so KorTTY never passes a link to the operating system's file opener, which would start programs and scripts: a file only ever opens as text in the Snippet Editor.

Linked text is drawn in the colors and with the bold, italic and inverse video the program chose for it, not in the terminal's default text color, and a color or attribute change in the middle of a link shows as well. A selection or a search match on a link is highlighted like other text. A link marked up with OSC 8 is underlined while you hold ++cmd++ (macOS) or ++ctrl++ (Windows and Linux) over it, which is when a click opens it. [Terminal recordings](recording.md) keep the colors, bold and inverse video of linked text too.

Rest the mouse on a link to see where it goes before you open it. The pointer turns into a hand, a web or e-mail address printed as plain text is underlined (a link marked up with OSC 8 while you hold ++cmd++ / ++ctrl++), and after half a second a tooltip shows **Cmd+click to open** (macOS) or **Ctrl+click to open** (Windows and Linux) and the address the link really opens. For a file the tooltip says **Cmd+click to open in the Snippet Editor** or **Ctrl+click to open in the Snippet Editor** and shows the path, or for an OSC 8 `file:` link its full `file://` target. For a link marked up with OSC 8 the tooltip is the only place that shows its target, because the program can print any text it likes for it. The tooltip writes an international host name in its `xn--` form, so a letter from another alphabet that only looks like a Latin one stands out, and it shows spaces, control characters and invisible direction-changing (bidi) characters as `%` codes; a very long address is shortened at the end, but its host is always shown in full. An address KorTTY does not open, such as an e-mail link with an `attach` field or a `file:` link to another host, gets no hand, and its tooltip says that KorTTY does not open it. The underline and the tooltip disappear when you move off the link, drag a selection, scroll, type or switch to another window, and they follow the text when new output moves it.

When the text of an OSC 8 link is itself a web address on another host than the link opens, for example `https://example.com/login` printed for a link to `https://login.example.net/` or to a `file:` path, a ++cmd++ / ++ctrl++ click asks before it opens the link. The question names the host the text shows and the address the link opens, in the same form as the tooltip, and **Cancel** is the default button, so pressing ++enter++ does not open the link. Host names are compared without regard to case, a final dot or a leading `www.`, and a link whose text is not a web address, such as a word or a file name, never asks.

Instead of holding a key you can right-click a link: the context menu then starts with **Open Link** and **Copy Link Address**. **Open Link** opens the link exactly as a ++cmd++ / ++ctrl++ click does, including the question above for a link whose text shows another host. **Copy Link Address** copies the address to the clipboard: for an OSC 8 link the address it really opens, in the form your browser receives (an international host name in its `xn--` form, as in the tooltip, but never shortened), not the text the program printed for it; for a web or e-mail address printed as plain text the address as printed, so an e-mail address is copied without `mailto:`. With the enterprise policy's [internal clipboard mode](../reference/enterprise-policy.md#internal-clipboard-mode) the address stays inside KorTTY. On a link to a file the two entries are **Open File in Snippet Editor**, which opens it as a ++cmd++ / ++ctrl++ click does, and **Copy Path**, which copies the path without a line number after it; for an OSC 8 `file:` link that is the path the link really opens, not the text the program printed. The menu acts on the link under the pointer at the moment you pressed the right mouse button, so new output that arrives before the menu opens does not change it, and it works while a program such as tmux or vim uses the mouse. A link that KorTTY does not open, such as an e-mail link with an `attach` field or a `file:` link to another host, gets neither entry.

## Quick select

Quick select copies or opens what the terminal shows without the mouse. Press ++cmd+shift+space++ (macOS) or ++ctrl+shift+space++ (Windows and Linux), or choose **Edit → Quick Select**: KorTTY puts a box around every web address, file path, e-mail address, UUID, IP address (IPv4 with an optional port, and IPv6), git commit hash and number of four or more digits that the focused pane shows, and a label of one or two letters over its first characters. Type a label to copy its text to the clipboard; **Copied** shows over it for a moment. Hold ++shift++ while you type the label's last letter to open a web address in your browser, an e-mail address as a new mail or, in SSH and local-shell tabs, a file path in the Snippet Editor, exactly as a ++cmd++ / ++ctrl++ click on it does; ++shift++ with the label of anything else copies it like the label alone.

The labels start at the bottom row, so the newest output gets the first ones. Up to 26 different texts each get one letter, home row first (`a`, `s`, `d`, `f`, ...), unless you chose [your own label letters](#your-own-patterns-and-label-letters); with more, every label has two letters, and the first letter you type hides the labels that do not start with it, while ++backspace++ takes it back. The same text shown in several places gets one label, and at most 676 different texts get one (with your own letters, their number times itself). A letter that no label continues with is ignored. Labels use the keys, not the characters they type, so Caps Lock never turns a copy into an open.

++esc++ ends quick select without doing anything, and so does any key that is no label letter, such as an arrow key, ++enter++, ++space++, a digit or a letter with ++ctrl++, ++alt++ or ++cmd++. Clicking into the pane, scrolling, switching to another tab or window, resizing the pane and changing the font size end it too. ++shift++, ++ctrl++ and the other modifier keys on their own change nothing, and holding the keys that started quick select does not start it again. When the program prints over a marked text while quick select runs, that text loses its box and label and the others keep theirs; when nothing is left, quick select ends.

While quick select runs, nothing you type reaches the program in the pane: not the label letters, not the characters they would type, not the text of an input method for Chinese, Japanese or Korean, and in [broadcast mode](#broadcast-mode) not the other panes either. Copies go through KorTTY's clipboard, so the enterprise policy's [internal clipboard mode](../reference/enterprise-policy.md#internal-clipboard-mode) keeps them inside KorTTY. Quick select reads the rows the pane shows at its scroll position, the scrollback included when you scrolled back, and leaves out a text that runs on above the top or below the bottom row rather than offering it cut short. It does not depend on **Detect web addresses, e-mail addresses and file paths in terminal text** in [Settings → Terminal → Links](../reference/settings/terminal.md#notes). It needs a terminal tab: in a snippet editor or file editor tab ++cmd+shift+space++ / ++ctrl+shift+space++ stays with the editor and **Edit → Quick Select** is disabled. When the keyboard is in a side panel of a terminal tab, such as the AI chat, the shortcut goes to that panel first, and **Edit → Quick Select** starts quick select from there.

### Your own patterns and label letters

Besides what it finds on its own, quick select can label text that only matters to you, such as ticket numbers, host names or order numbers. Enter one regular expression per line under **Quick select patterns** in [Settings → Terminal → Links](../reference/settings/terminal.md#notes), for example `[A-Z]+-\d+` for `JIRA-4711` or `web\d+\.prod\.example\.com` for your web servers. The patterns use Java's regular-expression syntax and are case-sensitive unless they start with `(?i)`. Every text a pattern matches in the rows the pane shows gets a box and a label like the built-in kinds; typing the label copies it, and so does ++shift++ with the label. A match stays within one line of output, and a line that wraps over several rows counts as one line. Spaces at either end of a match are left out; to label only part of what you look for, use a lookaround, such as `(?<=ticket #)\d+` for the digits after `ticket #`.

Where a match of your pattern overlaps a web address, path, number or other match quick select finds on its own, the longer one gets the label, and with the same length yours does: `JIRA-4711` is offered whole instead of the number in it, while a web address that contains it stays one web address. Where two of your patterns overlap, the longer match wins, and with the same length the pattern further up the list.

The settings accept up to 16 patterns of up to 512 characters each. A pattern that is not a valid regular expression, that also matches empty text such as `a*`, or that holds an invisible control character, which the settings file cannot store and which never appears on screen, is explained in red under the field with its line number, and **Save** keeps the dialog open until you correct or remove it. Because the text on screen comes from programs, also on servers, each pattern may take at most 10 ms on one line and all patterns together at most 100 ms for one quick select: a pattern that takes longer, such as `(a+)+$` on a long run of `a`, is skipped for the rest of that quick select, and quick select labels what it found until then. Changes apply the next time you start quick select.

**Quick select label letters** sets the letters the labels are made of, in the order they are used, for example `jfkdls` to keep the labels under one hand. It takes at least two different lowercase letters from `a` to `z`: an uppercase letter cannot be one, because ++shift++ with a label opens its match, and a digit, punctuation or any other key would end quick select. With n letters, up to n different texts get a one-letter label and at most n × n get a label at all. Leave the field empty for the default, `asdfqwerzxcvjklmiuopghtybn`.

## Font size and zoom

Adjust the font size of the active terminal on the fly without reconnecting:

| Shortcut | Action |
|----------|--------|
| ++alt+plus++ | Zoom in (increase font size) |
| ++alt+minus++ | Zoom out (decrease font size) |
| ++alt+0++ | Reset zoom to saved/default font |
| ++ctrl++ + mouse wheel | Zoom in/out over the terminal (Cmd + wheel on macOS) |

Holding ++ctrl++ (or ++cmd++ on macOS) and scrolling the mouse wheel over the terminal changes the font size — wheel up enlarges, wheel down shrinks — instead of scrolling the buffer. This complements the ++alt+plus++ / ++alt+minus++ / ++alt+0++ shortcuts.

**Reset zoom** restores the font size and family to what the connection had when you opened the tab (or the connection's saved settings, or the global default). The terminal context menu has the same controls: right-click → **Extras** → **Font Size** → **Increase**, **Decrease** (two points per step) or **Reset**. The zoom level applies to the current tab — all of its split panes change together — and leaves other tabs unchanged.

## Background transparency

**View → Zoom → Background Transparency** is a slider (0–100 %) that makes the terminal background see-through to the desktop while the text stays fully opaque and sharp. At 0 % the background is solid; higher values let more of the desktop show through. The value is saved and restored across restarts.

Only the terminal area becomes transparent — the title bar, menu bar, status bar and any tab without a terminal stay solid, so the window never turns into a see-through hole.

Horizontal, vertical and nested split terminals inherit the active transparency level, including panes added after transparency was enabled. Entering fullscreen with ++f12++ or terminal-only fullscreen with ++ctrl+shift+f++ temporarily renders the terminal area opaque without changing the saved value; leaving fullscreen restores that value to every pane.

Because a see-through window uses a different window style that the operating system fixes when the window opens, **switching transparency on or off (crossing 0 %) only takes full effect after a restart**; the status bar shows a hint when you cross that threshold. Adjusting the level while already in transparent mode applies live. In transparent mode the window uses a lightweight custom title bar (drag to move, buttons to minimise/maximise/close, double-click the strip to maximise, drag the edges to resize).

The slider lives in the in-window menu bar only (the native macOS menu bar cannot host a slider).

## Remote files sidebar

**View → Remote Files Sidebar** docks a narrow file list beside the panes of every terminal tab, on the right or the left. It shows the folder the shell of the focused SSH pane is in and follows it as you `cd` around. It is off by default; the position and the width you drag it to are remembered (**Settings → Terminal → Remote files sidebar**). Tabs with only local-shell or Mosh panes show no sidebar.

![The remote files sidebar beside a terminal pane, following the shell into /var/www/shop](../assets/screenshots/terminal/remote-sidebar.png)

The sidebar lists over an SFTP channel of the pane's own SSH session, like **Open SFTP Here**, so there is no second login, MFA prompt or access reason. The channel opens the first time the sidebar is shown in that tab and closes when you hide the sidebar or close the tab. A split pane connected elsewhere is followed with its own session when you click into it; clicking into a local or Mosh pane leaves the sidebar where it is.

How it follows the shell:

- **Folders the shell reports** (OSC 7 from a prompt hook, or korTTY's agent hook) are followed after a short pause of well under a second, in which korTTY first checks that the prompt is still your own session's, so a burst of `cd`s lists only the last folder.
- **A typed `cd`** is followed only once the next prompt shows the session's own user and host, or once a shell with [shell integration](shell-integration.md) marks its next prompt. A `cd` typed inside an editor, a container or a nested login, or one that fails, is never listed.
- **Another user or host** (after `su`, `sudo -i`, an inner `ssh` or a container prompt) pauses the sidebar: the banner says **Not following: another user or host is active** and the last listing stays. It checks the prompt again every second or two and follows again once the prompt shows the original user, for example after `exit`.
- **The pin** button stops following and keeps the folder shown; unpinning lists the folder the shell was last followed to.

The sidebar only browses: double-click a folder or click a part of the path above the list to look around, and type in the filter box to narrow the list by name. It never types into the terminal. A folder that has gone away keeps the last listing and shows **Folder not found** under it. Buttons in its header refresh the list, open the folder in the [SFTP manager](sftp.md) on the same session, upload files into the folder shown, download the selection, and hide the sidebar. Uploads and downloads go through the SFTP transfer list at the bottom of the sidebar after a confirmation that names the folder on the server, because the sidebar can lag behind the shell. Dropping files from the desktop onto the list uploads them the same way, and dragging a few small files out of the list copies them to the desktop. When your organization's policy switches file transfer off, the upload and download buttons are greyed out, drops are refused while you drag, and rows cannot be dragged out; browsing still works.

## Local shell tabs

Besides SSH and Mosh, a terminal tab can host a **Local Shell** — the local machine's own shell, opened via a pseudo-terminal (see [Local Shell](connections.md#local-shell)). A few terminal behaviors are local-shell aware:

- **++ctrl+d++ closes the tab for local cmd.exe/PowerShell sessions.** Those Windows shells do not exit on EOF, so ++ctrl+d++ would otherwise have no effect. For bash-family shells (Git Bash/Cygwin/WSL, macOS/Linux) and SSH, ++ctrl+d++ keeps its normal EOF meaning — the shell exits and the local tab then auto-closes.
- **Close confirmation** uses local-shell wording rather than "End SSH connection?", and the window-close prompt is transport-neutral ("Active sessions"), since one window can mix SSH, Mosh and local-shell tabs.
- **The current directory follows the interactive shell.** On macOS and Linux, korTTY refreshes it from the local shell process; native PowerShell and cmd prompts supply absolute Windows paths. After `cd`, `pushd`, `popd`, or `Set-Location`, **Open in Snippet Editor** resolves a selected file name against that current directory instead of the tab's start directory. If the directory cannot be determined or mapped safely, korTTY stops with an error rather than opening a same-named file from the wrong directory.
- **After an identity switch, Open in Snippet Editor is greyed out.** When the session no longer runs as the identity the tab was opened with — after `su`, an inner `ssh`, or a shell-opening `sudo` — the context-menu entry is disabled, in SSH tabs as well as local-shell tabs: the tab's tracked directories and file access still belong to the original login and would resolve the wrong path. The entry re-enables on its own once the prompt shows the original user again (typically after `exit`). A local-shell tab whose configured shell command is itself a remote client such as `ssh` or `mosh` keeps the entry disabled for the whole tab. If the load is triggered anyway, korTTY stops with an error instead of resolving the wrong path. The AI context-menu actions follow the same rule: they no longer offer to attach the selected file name's content (see [Attaching a selected file to the chat](ai-assistant.md#attaching-a-selected-file-to-the-chat)).
- **Clipboard text is preserved in agent shortcuts.** Typed and pasted text travel through the same terminal-input filter, including bracketed paste and split UTF-8 input, so a pasted file name remains part of the `agent ...` request and Enter dispatches it exactly once. In a connection with a single-byte [character encoding](connections.md#character-encoding) such as ISO-8859-1, every typed character is sent at once.

## Session journal

Every terminal tab can keep a [session journal](session-journal.md): server output and typed commands go into a capture log, an AI condenses them into a readable timeline, and screenshots and notes can be added from the journal bar or the terminal's right-click menu. Journals start automatically for connections that enable them, or retroactively for a running session via **Tools > Start/Stop Session Journal** — the existing scrollback is imported. See [Session journal](session-journal.md).

## Split-screen with broadcast

Split the terminal view to display multiple connections side by side, and optionally send input to all panes of the tab at once, or with [multi-exec](#multi-exec) to panes you choose in any tab and window.

### Split operations

- **Split Pane**: right-click a pane and choose *Extras → Split Terminal*, then **Split Right** or **Split Down**, each to the same server or to a new connection. Splits nest, so a tab can hold a grid of panes. The [command palette](command-palette.md#terminal-and-tab-commands) splits the focused pane with the *View → Panes* commands of the next point, such as **Split Right**.
- **Split from the keyboard**: ++ctrl+shift+o++ (++cmd+shift+o++ on macOS) splits the pane the keyboard is in, on that pane's own server, as **Split Right (same server)** or **Split Down (same server)** does. The new pane goes to the right of a wide pane and below a tall one, so the panes stay roughly square when you split again and again, and the keyboard moves into the new pane. *View → Panes* has the same command as **Split Pane**, and **Split Right** and **Split Down** to choose the side yourself. The key works in every terminal tab while the keyboard is in it; on Windows and Linux it no longer reaches the shell, which received it as ++ctrl+o++ and still gets that key itself.
- **Independent Sessions**: Each pane can show a different SSH connection.
- **Same server means the pane's server**: **Split Right (same server)** and **Split Down (same server)** open a new session to the server of the pane you split, with the sign-in that pane used. For a pane you opened with **Split Right (new connection)** or **Split Down (new connection)**, that is the server you picked for it, not the tab's, and the panes split from it stay on that server too; every other pane splits to the tab's server. If the pane signed in with a temporary SSH key that has expired since, the split says so and opens no pane.
- **Panes of another color**: a pane opened with **Split Right (new connection)** or **Split Down (new connection)** to a connection whose [tab color](connections.md#tab-color) differs from the tab's, and the panes split from it on the same server, show a 3-pixel frame of that color at their edge, with the focus ring inside it. The frame lies over the edge of the pane like the ring, so it never resizes the terminal, and it stays with its pane when you split, move, zoom or close other panes. The tab's tooltip lists these panes with their connection and color, and screen readers hear both after the pane's name, for example *Pane 2 of 2, Connection db-prod, tab color red (#D32F2F)*.
- **Resizable Panes**: Drag dividers to adjust pane sizes.
- **Close Pane**: the × in a pane's top-right corner, *Extras → Split Terminal → Close Split* in its context menu, *View → Panes → Close Pane* for the pane the keyboard is in, or `exit` in its shell closes that pane, without asking first. The × shows only while the tab has more than one pane, and **Close Split** and **Close Pane** are disabled in the last one; typing `exit` in the last pane closes the tab. After **Close Pane** the keyboard moves to the first remaining pane.
- **Move between panes from the keyboard**: ++ctrl+alt++ with an arrow key (++cmd+option++ on macOS) moves the keyboard focus to the pane on that side; at the edge of the tab the focus stays where it is. *View → Panes* has the same commands, and **Next Pane** and **Previous Pane**, which go through all panes of the tab and start over after the last one. With a single pane the keys reach the program in the terminal; see [Keyboard shortcuts](../reference/keyboard-shortcuts.md#panes) for desktops that take them first.
- **Focus ring**: in a tab with two or more panes, a blue ring marks the pane the keyboard is in. While the keyboard is somewhere else, for example in another window or a side panel, a dimmer ring stays on the pane the menu commands act on. The ring is drawn over the edge of the pane and never changes the size of its terminal, and it is not shown while a pane is zoomed. Screen readers name each pane *Pane 2 of 3*, numbered as in the [Dashboard](../getting-started/main-window.md#dashboard).
- **Zoom a pane**: ++ctrl+shift+enter++ (++cmd+shift+enter++ on macOS) or *View → Panes → Zoom Pane* lets the pane the keyboard is in fill the whole tab for a while; press it again to show all panes, each divider where it was. This is not the [font zoom](#font-size-and-zoom): only the zoomed pane gets bigger. A badge at its top right, such as *Zoomed · hidden panes: 2*, shows that other panes are hidden, and the check mark at **Zoom Pane** shows it in the menu. The hidden panes keep running at the size they had, so their programs see no change of the window size. Splitting, closing or moving a pane, moving the keyboard to another pane (with the keys, **Next Pane** and **Previous Pane**, the Coding Agents panel or the Control API) and dragging a pane show all panes again. The key needs two or more panes; with a single pane it reaches the program as ++enter++. A [project](projects.md) saved while a pane is zoomed stores all panes with their dividers, without the zoom. Screen readers hear the badge with the pane's name.
- **Focused pane**: *Edit → Copy*, *Edit → Paste*, *Edit → Find...*, the AI actions and a recording of the active split act on the pane that has, or last had, the keyboard focus, the one your typing goes to, however the focus got there (a click, a middle-click or a jump from the Coding Agents panel). Closing another pane leaves them on that pane; when the focused pane itself closes, they move to the first remaining pane. The terminal's right-click menu acts on the pane you right-clicked.
- **Access reason asked once per tab**: when a server asks for a reason for the connection, as a CyberArk-style jump host does, a split does not ask again. korTTY sends the reason that was given when the tab was opened, because a server that asks for one closes a session that answers with nothing. A split to a different server, or a server asking something else, is asked once as well, and a new tab always starts by asking. If the server refuses the reason, for example because a ticket number has expired in the meantime, korTTY drops it and asks again on the next attempt.
- **Move Panes**: Hold ++shift+alt++ (Windows/Linux) or ++shift+option++ (macOS) and drag a pane onto another to reorder. Without the modifiers, mouse drag is used for text selection in the terminal.
- **Saved with projects**: a [project](projects.md#split-panes) stores each tab's panes, the server of each pane and the position of every divider, and opening it brings the panes back once the tab is connected. The access reason the tab was given is used for its panes as well.

### Broadcast mode

When **Broadcast Mode** is enabled, keyboard input is sent simultaneously to all panes of the tab. This is useful for running the same commands on multiple servers. Switch it on or off with *Extras → Broadcast Mode* in a pane's right-click menu or with *View → Panes → Broadcast to All Panes of This Tab*, which the [command palette](command-palette.md#terminal-and-tab-commands) runs too; both need two or more panes to switch it on, and the check mark shows whether it is on for the active tab.

- **Mirrored**: typed text, ++enter++, ++backspace++, ++esc++, ++tab++ and ++shift+tab++, the arrow keys, ++home++ / ++end++, ++page-up++ / ++page-down++, ++insert++ / ++delete++ and ++f1++ to ++f11++, including their ++shift++, ++ctrl++ and ++alt++ combinations (++f12++ toggles fullscreen).
- **Encoded for each pane**: every pane receives a key the way its own program expects it. When one pane runs `mc` or `vim`, which switch the terminal to application cursor keys, its arrows arrive as `ESC O A` while a shell in the next pane gets `ESC [ A`, so history and completion work in both.
- **Kept local**: the scrollback keys (++shift+page-up++ / ++shift+page-down++, and ++ctrl+up++ / ++ctrl+down++ on Windows and Linux or ++cmd+up++ / ++cmd+down++ on macOS) scroll only the focused pane. The same goes for the prompt keys ++ctrl+shift+up++ / ++ctrl+shift+down++ (++cmd+shift+up++ / ++cmd+shift+down++ on macOS) while the focused pane has [prompt marks](shell-integration.md); in a pane without them they reach the program and, on Windows and Linux, are mirrored like any other arrow key. A full-screen program such as `vim` or `less` has no scrollback, so while one runs in the focused pane, ++shift+page-up++ / ++shift+page-down++ (and ++ctrl+up++ / ++ctrl+down++ on Windows and Linux) go to that program and, like the other keys, to every other pane.
- **Not mirrored**: paste and snippets go only to the focused pane, and what you type into the find bar stays in the find bar. A key goes to the other panes only when the pane you type in sends it to its own program as well, so the copy and paste keys (++ctrl+shift+c++ / ++ctrl+shift+v++ on Windows and Linux) and the keys of menu commands send nothing to them.
- **Marked on the panes and the tab**: while broadcast mode is on, every pane of the tab shows an amber outline and a *Broadcast* badge at its top right, and the tab shows an amber fork icon in its header whose tooltip says that what you type goes to all its panes. Screen readers hear *Broadcast* after the pane's name.
- **Panes that must not get your keys are left out**: broadcast mode sends nothing into a pane that is sending a paste line by line (see [Pasting into slow devices](#pasting-into-slow-devices)), so none of your keys lands between two pasted lines. It also leaves out a pane where a korTTY [AI agent](ai-assistant.md#ai-agent-and-ai-planning) run is active, so what you type for the other panes cannot interfere with the commands it runs, and a pane whose [coding agent](coding-agents.md) waits for your decision (**BLOCKED**), where a mirrored ++y++ and ++enter++ would approve whatever it asks. The pane gets your keys again once the paste is done, the run has ended or the agent no longer waits. While panes are left out, *Extras* in a pane's right-click menu says how many below **Broadcast Mode**, for example *Panes left out right now: 1 of 3*. What you type in such a pane yourself still goes to the other panes.
- **An AI agent starts only where you type**: the [agent shortcut](ai-assistant.md#terminal-agent-shortcut-commands), such as `agent restart nginx` and ++enter++, starts one run, in the pane you type it in. The other panes receive the line like any other command line, so their shell runs it as a command (and usually reports that there is no command `agent`), but no run starts in them.
- **A password goes only to panes that ask for one**: while the cursor of the pane you type in is on a password prompt, such as `[sudo] password for anna:` (a short line ending with a colon that mentions a password, passphrase or PIN), your keys, ++enter++ included, go only to the panes whose cursor is on a password prompt too. In a pane at an ordinary shell prompt the password would be shown, run as a command and kept in the shell history and the [session journal](session-journal.md). Like the session journal, korTTY recognises the prompt by its text, because no server tells the terminal that it stopped echoing what you type.
- **A stalled pane does not freeze korTTY**: the keys for the other panes are sent in the background, to each pane in the order you typed them, so a server that stops responding holds up only its own pane while the window and the other panes keep reacting.
- **Hidden panes still receive the keys**: while one pane is [zoomed](#split-operations), what you type in it still goes to the hidden panes, in broadcast mode and as fellow members of [multi-exec](#multi-exec). Its badge then says how many of them receive your input, for example *Zoomed · hidden panes: 2, receiving your input: 2*, and turns amber. Panes left out for one of the reasons above are not counted.

### Multi-exec

Multi-exec sends what you type in one pane to other panes you choose, in any tab of any window, for example to run the same commands on several servers that are open in different tabs. Broadcast mode takes all panes of one tab; with multi-exec you choose each pane, and the panes you chose type into each other: what you type in any of them goes to all the others.

- **Choose the panes**: *Extras → Multi-exec: Include This Pane* in a pane's right-click menu, *Multi-exec: Include All Panes of This Tab* in a tab's right-click menu, or *View → Multi-exec* with **Include This Pane** for the pane the keyboard is in, **Include All Panes of This Tab** and **Include All Terminals of This Window**. In the [Dashboard](../getting-started/main-window.md#dashboard), the right-click menu of a pane row or a connection row does the same for any pane or tab of any window, without switching to it. Choosing **Include This Pane** or **Include All Panes of This Tab** again takes the pane, or the tab's panes, out again, and the check marks show whether they take part.
- **Always marked**: a pane that takes part shows an amber outline and a *Multi-exec* badge at its top right, left of the zoom badge while it is zoomed. Its tab shows an amber fork icon in its header, whose tooltip says how many of the tab's panes take part, and its Dashboard row shows the same icon. While any pane takes part, the status bar of every window shows a chip such as *Multi-exec · panes: 3 · tabs: 2 · windows: 1*. Multi-exec asks for no confirmation; the markers stay as long as it runs. Screen readers hear *Multi-exec* after the pane's name.
- **Stop with one click**: **Stop** in the status-bar chip, or *View → Multi-exec → Stop Multi-exec*, takes every pane of every window out. Closing a pane, its tab or its window takes it out as well; a tab you drag to another window keeps its panes in.
- **What goes to the other panes**: the same keys as in [broadcast mode](#broadcast-mode), each encoded for the program in each pane, and only from a pane that takes part. Paste, snippets, the text of an input method for Chinese, Japanese or Korean and text sent through the [Control API](../reference/control-api.md) stay in their pane.
- **The same safety rules as broadcast mode**: a pane that is sending a paste line by line, where a korTTY AI agent run is active, or whose coding agent waits for your decision gets nothing meanwhile, and the status-bar chip says how many panes are left out, for example *left out right now: 1*. The [agent shortcut](ai-assistant.md#terminal-agent-shortcut-commands) starts a run only in the pane you type in, and while that pane shows a password prompt your keys go only to the panes at a password prompt too.
- **A stalled server does not freeze korTTY**: the keys are sent to each pane in the background, in the order you typed them, so a server that stops responding holds up only its own pane.
- **Together with broadcast mode**: multi-exec and a tab's broadcast mode can be on at the same time; a pane that both would reach gets each key once.
- **Turned off by your organization**: an [enterprise policy](../reference/enterprise-policy.md#rulefeatures) with `multi-exec = "deny"` locks every way to let a pane take part, in the menus, the right-click menus, the Dashboard, the command palette and the keyboard shortcuts, and broadcast mode can no longer be switched on. **Stop Multi-exec** keeps working.
- **One notification for a command typed once**: a long-running command you type into the panes that take part leads to one desktop notification, not one per pane, and a bell with which the other panes answer your mirrored keys leads to none; see [Terminal notifications](terminal-notifications.md#long-running-commands).

## Terminal effects

Terminal effects can change the visible terminal style and output animation. Effects are Java plugins managed from **Plugins > Terminal Effects**.

### User controls

- **Current terminal**: Use **View > Terminal Effect** or the terminal context menu to choose an effect for the active terminal.
- **Quick Connect**: Choose the effect and speed before opening a temporary or saved connection.
- **Connection Manager**: Store the effect and speed on a saved connection so new tabs use it automatically.
- **Speed**: Use the slider for `1x` through `10x`; if that is still too slow, type a custom value up to `99x` in the numeric speed field.

### Plugin management

- Open **Plugins > Terminal Effects** to manage plugins.
- The table lists loaded plugins with active state, name, and description.
- **Disable** a plugin to keep it installed but unavailable for activation.
- **Import** external `.jar` plugins. KorTTY copies them into `~/.kortty/plugins`.
- **Export** plugins that have a source JAR. The bundled MOTHER effect is exportable.

!!! warning
    Imported terminal-effect plugins are trusted Java code and are not sandboxed. Only import plugins from sources you trust.

For detailed plugin development documentation, see [Terminal effect plugins](terminal-effect-plugins.md).

## SSH keep-alive

Prevent connections from dropping due to inactivity by configuring SSH keep-alive messages:

1. Enable **SSH Keep-Alive** in the connection's **Terminal** tab or in **Settings > Terminal**.
2. Set the interval (5 to 600 seconds, default: 60).
3. KorTTY sends `SSH_MSG_IGNORE` heartbeat messages at the configured interval and enables TCP socket keepalive while the option is active.

!!! note
    If a server, firewall, VPN, or NAT gateway closes idle sessions sooner than the configured interval, the connection can still end. In that case, check the server-side SSH configuration and network idle-timeout settings as well as the KorTTY log.

## Terminal logging

Writes a connection's terminal output to a file, for audit and debugging. This is independent of the [Session Journal](session-journal.md): it is a plain transcript with no summaries, markers or screenshots, and the two can run at the same time.

Configure it in either place:

- **Connection Manager > Edit connection > Logging** for a saved connection.
- **Quick Connect > Terminal log** for a one-off session, or to change the setting for the connection you are about to open.

1. Enable logging.
2. Choose a **log folder**. Left empty, KorTTY uses `~/.kortty/terminal-logs`. You pick the folder; the file names are KorTTY's.
3. Choose a log format:
   - **Plain Text** - One timestamped line per line of output.
   - **XML** - Structured XML with timestamps.
   - **JSON** - Structured JSON with timestamps.
4. Optionally adjust the **maximum file size** (default: 10 MB) and the **retention period** (default: 30 days), and turn off **Start a new file every day** or **Compress closed files (gzip)** — both are on by default. Quick Connect's Terminal log section covers enable, folder, format and compression; size limit, retention and daily rotation keep their configured or default values.

### File names

Every file is named `<date>-<time>-<server>_<number>`, for example `2026-08-04-14-30-12-web01_1.log.gz`. The date leads so a folder listing sorts chronologically, and the trailing number distinguishes connections that are open at the same time — two tabs on the same server get `_1` and `_2` and never write into one another's file.

### Rotation, compression and retention

By default a new file is started **every day**, and always again whenever the maximum size is reached (those parts are numbered `.p2`, `.p3`, …); daily rotation can be turned off to roll only by size. Nothing is ever overwritten or deleted by rotation.

Closed files are gzipped by default; the file currently being written always stays uncompressed so that a crash cannot truncate it. Turn off **Compress closed files (gzip)** to keep finished files as plain text instead. A connection that produces no output creates no file at all.

Files older than the retention period are deleted automatically when a connection starts and after each daily rollover. Set the retention to `0` to keep everything. Only KorTTY's own log files are ever removed — anything else in the folder is left alone, so it is safe to point the setting at a folder you also use for other things.

### What is removed before writing

Captured lines go through the same redaction as the [Session Journal](session-journal.md), on the capture thread, before anything is buffered or written: the connection's own password and any replacement rules your organisation's policy defines. The secret never reaches the file, so there is nothing to clean up afterwards.

Log files and a log folder KorTTY created itself are set to owner-only permissions where the filesystem supports it. A folder you chose yourself is left with the permissions you gave it.

!!! warning "Redaction only covers what KorTTY knows"
    A password KorTTY stores for the connection is redacted. A secret you type into a command yourself, or one a program prints, is not — KorTTY has no way to recognise it. Treat the log folder as sensitive, and use policy replacement rules for patterns that recur.

## Terminal recording

Terminal recording is designed as a low-resource replay feature. KorTTY records terminal screen-state changes and timing events into one JDK/GZIP streaming-compressed `.korttyrec.jsonl.gz` file per terminal tab session. Legacy `.korttyrec.jsonl` replay files remain readable.

### Configure recordings

1. To enable recording automatically after every app restart, open **Settings > Video** and enable **Enable terminal recording after app restart**.
2. To enable recording only for this session, open **Tools > Video Manager...** and select **Enable terminal recording for this app session**.
3. Set the **Storage path**. If left at the default, KorTTY uses `~/.kortty/recordings`.
4. Choose the **Default scope** (active split or whole tab). Recordings are always KorTTY replay files; video export is a separate step and requires `ffmpeg`.
5. Enable or disable **Auto-pause when the terminal is idle** and set the idle threshold (default: 20 seconds).
6. Optional: enable **Capture terminal colors in new recordings** if exported videos should reproduce terminal colors.
7. Optional: set the `ffmpeg` path and click **Check**. If `ffmpeg` is missing, video export stays disabled but replay files remain usable.
8. Click **Save**.

### Start and stop recording

1. Open or focus an SSH terminal tab.
2. If terminal recording is enabled, click **Start recording** in the terminal bar, choose **Tools > Start/Stop Terminal Recording**, or press ++ctrl+shift+e++ (Cmd+Shift+E on macOS).
3. If the tab contains multiple split terminals, choose whether to record only the active split or the whole tab.
4. Click **Stop recording** or press ++ctrl+shift+e++ again to stop the current segment.
5. Start and stop as often as needed in the same tab. KorTTY appends all segments to the same replay file until the tab closes.

### Export a video

1. Open **Tools > Video Manager...**.
2. Select a `.korttyrec.jsonl.gz` replay file from the list.
3. Verify that the ffmpeg status says video export is enabled.
4. Click **Export...**.
5. In the export options:
   - Choose **Export entire recording** or enter start/end times with minute or `MM:SS` format.
   - Choose whether to include terminal colors (available only if the replay contains color data).
   - Choose **WebM/VP9** or **MKV/FFV1** format, then select an output path.
6. While KorTTY renders frames and runs `ffmpeg`, the export progress dialog shows the current phase, progress bar, and estimated remaining time. Export uses the recorded terminal geometry so large terminal screens are not cropped.

### View and manage recordings

1. Open **Tools > Video Manager...**.
2. Select a `.korttyrec.jsonl.gz` replay file.
3. Click **View** to play the replay directly inside KorTTY.
4. Use the replay viewer timeline to scrub, or enter a **Time jump** value such as `5` for minute 5 or `5:30` for minute 5 and 30 seconds.
5. Set **Speed** between `1x` and `20x` to control playback speed.
6. Click **Rename...** to rename the replay file.
7. Click **Delete** to delete the selected replay after confirmation.
