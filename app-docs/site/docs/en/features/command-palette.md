---
title: Command palette
---

# Command palette

The command palette finds and runs any command of the main window's menus when you type a few letters of its name, so you need neither the mouse nor the place of the command in the menus. It also runs the right-click commands of the terminal you are in, such as **Clear Buffer** or a split, switches to any open tab, the ones you used last first, including the terminal tabs of your other korTTY windows, and opens a tab for any saved or shared teamwork connection. It reaches every menu command while the menu bar is hidden and in terminal-only fullscreen.

## Opening the palette

- Press ++ctrl+shift+p++ (++cmd+shift+p++ on macOS), or choose **View → Command Palette…**. The same keys close it again.
- The keys work in every tab of a main window, also while a terminal, a snippet editor or another tool tab has the keyboard focus, with the menu bar hidden and in terminal-only fullscreen. A tool that opens in a window of its own, instead of as a tab, has no palette.
- On Windows and Linux korTTY keeps ++ctrl+shift+p++ for itself, so it does not reach the program in the terminal, while plain ++ctrl+p++ (the previous command in bash) still reaches the shell. ++alt-graph++ combinations, which arrive as ++ctrl+alt++, are left alone too.

The palette opens at the top of the window with an empty search field. On macOS, when the menu bar of a window you closed is still shown, **View → Command Palette…** opens the palette in the frontmost open window.

## Finding a command

Type part of a command's name. The letters have to appear in that order but not next to each other, and case does not matter, so `nwin` finds **New Window**. Each row shows:

- a **Command** badge;
- the command's name as its menu shows it, followed by its menu path in grey, for example **Dock Left** View › Live Journal;
- a check mark when the command is a setting that is on, such as **Show Dashboard**;
- its keyboard shortcut, if it has one.

The menu path is searched as well, which keeps the commands with the same name apart: `journal left` finds **View › Live Journal › Dock Left**, and `file left` finds **View › File Browser › Show on Left**. A match in the name ranks above a match in the path, and among equally good matches the commands you chose recently come first.

With nothing typed, the palette lists the commands and connections you chose recently, up to eight and newest first, then the [open tabs](#switching-tabs), and then every command, menu by menu. Other connections only appear once you type. Next to the menu commands it offers **Next Tab** and **Previous Tab**, which switch tabs like ++ctrl+tab++ and ++ctrl+shift+tab++, and the [terminal and tab commands](#terminal-and-tab-commands).

Typed first, a scope character limits the list to one kind of row: `>` lists only commands, `#` only tabs and `@` only connections, so `>close` finds the close commands but no tab whose name contains "close". The line below the list names these characters.

The list of recent choices lasts until korTTY quits; all windows share it and it is never saved. What you type into the palette is neither logged nor sent anywhere.

## Terminal and tab commands

The palette also offers the commands of the terminal's and the tab's right-click menus that the menu bar does not have. They act on the terminal tab you are in, and the terminal commands on its focused pane, the one your typing goes to and that **Edit → Find…** searches:

| Command | Grey detail | What it does |
| --- | --- | --- |
| **Clear Buffer** | Terminal | Clears the scrollback and the screen of the focused pane but keeps the prompt line, like **Clear Buffer** in the terminal's right-click menu. While a full-screen program such as `vim` or `less` runs, it does nothing. On macOS the row shows the terminal's own key, ++cmd+k++. |
| **Split Right (same server)** / **Split Down (same server)** | Terminal | Opens a new session to the same server in a new pane to the right of the focused pane or below it, exactly like these entries under **Extras → Split Terminal** in the right-click menu, with the same progress dialog and the same [server policy](terminal.md#connecting-safely) check. |
| **Broadcast Mode** | Terminal | Switches [broadcast mode](terminal.md#broadcast-mode) of the tab on or off and shows a check mark while it is on. Switching it on needs a second pane; switching it off always works, also when only one pane is left. |
| **Duplicate** | Tabs | Opens a copy of the tab next to it and signs in like **Duplicate** in the tab's right-click menu. |
| **Reconnect** | Tabs | Connects the tab again, like **Reconnect** in its right-click menu. |

In any other kind of tab, such as a snippet editor, these rows are greyed out. **Find** is not listed a second time: type `find` for **Edit → Find…**, which opens the search of the focused pane.

## Switching tabs

Every tab of the window is a row with a **Tab** badge, named as the tab bar names it: the name you gave it with **Rename Tab**, the title its shell set, or the name of its connection, without the group prefix and the connection status. The grey detail of a terminal tab names where it is connected, as `user@host` taken from the saved connection (or the connection's name for a local shell), and its tab group, for example **root@db-01 · Production**. A program in the terminal can set the tab's title but not this detail, so a title that imitates another server does not hide where the tab really is.

The tabs are listed in the order you last used them, the most recent first, and when you type, equally good matches keep that order. The tab you are in comes last and is marked **Current tab**, so typing `#` and pressing ++enter++ goes back to the tab you used before it. A tab counts as used when you select it, with the mouse, with keys such as ++ctrl+tab++ or ++ctrl+1++, or from the palette. With [++ctrl+tab++ in this order](../reference/settings/window.md#tabs) switched on, stepping through the tabs with ++ctrl++ held counts only the tab you stop at. Closing several tabs at once, regrouping them or dragging a tab to another place counts only the tab shown afterwards, not the tabs the selection passes over on the way. The order lasts as long as the window and is never saved.

The terminal tabs of your other korTTY windows follow, each window's tabs in the order they were used there, with the window's number in front of the detail, such as **Window 2 · admin@web-01**; a window's number is its place among the open windows, in the order they opened. Choosing such a tab brings its window to the front, restores it when it is minimized, and selects the tab there. Other kinds of tabs of the other windows, such as editors or AI results, are not listed.

## Connecting

Every connection saved in the [Connection Manager](connections.md#connection-manager) is a row with a **Connection** badge, named as the Connection Manager names it. The grey detail says where it connects, as `user@host` (**Local Shell** for a local shell), and its group, for example **postgres@db-01.example.org · Production**; a connection without a name of its own shows only its group there, because its name already is `user@host`. The detail never shows a password, a stored credential or an SSH key.

When your organization's policy allows [teamwork](teamwork.md), the connections shared with you follow, with **Shared (Teamwork)** in front of the detail. Their names come from a file someone else writes, so their rows always name `user@host` as well, even when the name looks like an address. Teamwork connections you deleted on this computer are not listed.

Saved connections are listed with the one you used last first, and the ones you never used by name; the teamwork connections follow by name. When you type, the connections you chose in the palette recently come first among equally good matches, and the others keep that order. Type `@` to list only connections, the ones you chose in the palette recently first.

Choosing a connection opens a tab for it in this window and signs in exactly like **Connect** in the Connection Manager (see [Signing in](connections.md#signing-in)): korTTY checks the server policy first, then uses the saved password or key and asks only for what is missing. Cancelling a question opens no tab. Unlike the Connection Manager, a connection opened from the palette counts as used, so it moves up in the palette and among Quick Connect's frequently used connections.

A connection whose server or jump server your organization's [server access policy](../reference/enterprise-policy.md#server-access-control) blocks is greyed out. Choosing it keeps the palette open, and the line below the list names the blocked server and says that your organization manages it.

## Running a command

| Key | Action |
| --- | --- |
| ++up++ / ++down++ | Choose a row |
| ++enter++ or a double click | Run the chosen row, or the first row when none is chosen |
| ++tab++ / ++shift+tab++ | Move between the search field and the list |
| ++esc++ | Close the palette |
| ++ctrl+shift+p++ (++cmd+shift+p++ on macOS) | Close the palette |

The palette closes first and then runs the command exactly as its menu item does: a dialog opens, a setting such as **Show Dashboard** is switched, and **Edit → Find…** opens the search of the active terminal. A tab row selects its tab, and a connection row opens a tab for its connection. A click outside the palette closes it as well.

Commands that cannot run right now are greyed out, such as **Unlock Vault…** while the vault is already open or **Rename Tab…** outside a terminal tab. Choosing one keeps the palette open, and the line below the list says why: **Not available right now**, or that your organization manages the feature when its [policy](../reference/enterprise-policy.md) switched the feature off.

!!! note "Keys stay in the palette"
    While the palette is open, every key you press goes to the palette. ++ctrl+d++, ++ctrl+l++, ++page-up++ or a function key never reach the terminal behind it, nor the other panes in [broadcast mode](terminal.md#broadcast-mode), and closing the palette with its shortcut leaves no character in the terminal either.

## Clear Buffer and Find on Windows and Linux

On Windows and Linux ++ctrl+l++ and ++ctrl+f++ go to the program in the terminal, so the terminal's **Clear Buffer** and its search have no key of their own there. Open the palette and type `clear` for **Clear Buffer** or `find` for **Edit → Find…** to reach them from the keyboard.
