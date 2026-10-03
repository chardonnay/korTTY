---
title: Command palette
---

# Command palette

The command palette finds and runs any command of the main window's menus when you type a few letters of its name, so you need neither the mouse nor the place of the command in the menus. It also reaches every menu command while the menu bar is hidden and in terminal-only fullscreen.

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

With nothing typed, the palette lists the commands you chose recently, up to eight and newest first, and then every command, menu by menu. Next to the menu commands it offers **Next Tab** and **Previous Tab**, which switch tabs like ++ctrl+tab++ and ++ctrl+shift+tab++.

The list of recent choices lasts until korTTY quits; all windows share it and it is never saved. What you type into the palette is neither logged nor sent anywhere.

## Running a command

| Key | Action |
| --- | --- |
| ++up++ / ++down++ | Choose a row |
| ++enter++ or a double click | Run the chosen row, or the first row when none is chosen |
| ++tab++ / ++shift+tab++ | Move between the search field and the list |
| ++esc++ | Close the palette |
| ++ctrl+shift+p++ (++cmd+shift+p++ on macOS) | Close the palette |

The palette closes first and then runs the command exactly as its menu item does: a dialog opens, a setting such as **Show Dashboard** is switched, and **Edit → Find…** opens the search of the active terminal. A click outside the palette closes it as well.

Commands that cannot run right now are greyed out, such as **Unlock Vault…** while the vault is already open or **Rename Tab…** outside a terminal tab. Choosing one keeps the palette open, and the line below the list says why: **Not available right now**, or that your organization manages the feature when its [policy](../reference/enterprise-policy.md) switched the feature off.

!!! note "Keys stay in the palette"
    While the palette is open, every key you press goes to the palette. ++ctrl+d++, ++ctrl+l++, ++page-up++ or a function key never reach the terminal behind it, nor the other panes in [broadcast mode](terminal.md#broadcast-mode), and closing the palette with its shortcut leaves no character in the terminal either.

## Find on Windows and Linux

On Windows and Linux ++ctrl+f++ goes to the program in the terminal, so the terminal's search has no key of its own there. Open the palette and type `find` to reach **Edit → Find…** from the keyboard.
