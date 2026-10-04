---
title: Keyboard
---

# Keyboard

This tab lets you choose your own shortcuts for korTTY's commands: give a menu command other keys, give a command without a shortcut one, or remove a shortcut you keep pressing by accident. Open via **Configuration → Global Settings → Keyboard**; stored in `~/.kortty/global-settings.xml`.

![Keyboard settings tab](../../assets/screenshots/settings/keyboard.png)

| Setting | Type | Values | Default | Stored as |
| --- | --- | --- | --- | --- |
| Shortcut of a command | key combination | a key with ++ctrl++, ++alt++ or ++cmd++, ++f1++ to ++f12++ on their own, or none | the shortcut its menu shows | `keyBindingOverrides` (one `binding` per changed command) |

The list holds every command of the window's menu bar, also the ones without a shortcut, in menu order. Its columns are the **Action**, the **Menu** it is in (its menu path, as the [command palette](../../features/command-palette.md) shows it), the **Shortcut** and a **Status**: empty for a command on its default shortcut, **Changed** or **Removed** for one you changed, **Fixed** for a shortcut that cannot be changed, **Also used by …** for a [conflict](#conflicts) and **Not used here** for a stored shortcut this computer [does not accept](#shortcuts-from-another-computer-or-version). Type part of a command's name, its menu or its keys into **Filter by action, menu or shortcut** to find it, for example `dashboard`, `view` or `shift+cmd+d`, and tick **Only changed** to list only the commands you changed.

## Changing a shortcut

1. Select the command in the list.
2. Click the shortcut field below the list and press the new combination. Holding a modifier on its own records nothing, so press ++ctrl++, ++alt++, ++shift++ or ++cmd++ together with the key.
3. Click **Save**.

The field takes the key press as the command's new shortcut and the list shows it as **Changed**; a combination korTTY cannot use is [refused](#shortcuts-kortty-refuses) with the reason below the field, and the command keeps its shortcut. While the field records, ++esc++ ends recording without a change and ++tab++ leaves the field. **Remove Shortcut** leaves the command without a key, **Reset to Default** gives it its own shortcut back, and **Reset All** puts every command back on its default. Pressing the default shortcut in the field is the same as **Reset to Default**. The line below the field shows the default shortcut of the selected command.

Saving applies the shortcuts at once in every open window, without a restart: the menus show the new keys, and so does the command palette. The commands that also work while a terminal has the focus and with the menu bar hidden follow your shortcuts too: the command palette, **Show Menu Bar**, **Terminal-only Fullscreen**, **Highlighting On**, **Credentials**, **Reopen Closed Tab**, **Quick Select** and the pane commands of *View → Panes*. For these, whatever character your shortcut would type is kept out of the terminal, so it never reaches the shell or, in broadcast mode, the other panes.

## Shortcuts korTTY refuses

korTTY does not take a key away from the shell or the system, so these combinations are refused when you record them:

| Combination | Why |
| --- | --- |
| ++ctrl++ with a letter (++ctrl+l++, ++ctrl+f++, ++ctrl+d++, ++ctrl+p++, ++ctrl+r++, ++ctrl+c++, ++ctrl+v++ and every other letter; on macOS the Control key, not ++cmd++), ++ctrl+space++, ++ctrl++ with `[`, `]`, `\` or `/`, ++ctrl+shift+6++, ++ctrl+shift+2++ and ++ctrl+shift+minus++ | They send control characters the shell and the programs in the terminal use; ++ctrl+shift+6++ is the Cisco break sequence |
| ++ctrl++ or ++alt++ with ++left++, ++right++, ++backspace++ or ++delete++ | They move or delete a word at the shell's prompt |
| ++alt++ with a letter, digit or punctuation key on Windows and Linux | korTTY sends it as the shell's Meta key |
| ++ctrl+alt++ with a character key on Windows, ++option++ with a character key on macOS | They type a character on many keyboard layouts: on Windows ++alt-graph++ arrives as ++ctrl+alt++ |
| ++cmd+c++, ++cmd+v++, ++cmd+k++, ++cmd+f++, ++cmd+up++ and ++cmd+down++ on macOS, ++ctrl+shift+c++, ++ctrl+shift+v++, ++ctrl+up++ and ++ctrl+down++ on Windows and Linux, ++shift+page-up++ and ++shift+page-down++ everywhere | The terminal's own keys for copying, pasting, clearing, finding and scrolling |
| ++cmd+tab++, ++cmd+space++, ++cmd+h++, ++cmd+shift+q++, ++cmd+option+d++, ++ctrl+up++, ++ctrl+down++ and the screenshot keys on macOS, ++alt+tab++, ++alt+f4++, ++alt+space++ and ++ctrl+esc++ on Windows, ++alt+tab++, ++alt+f4++, ++ctrl+alt+delete++ and ++ctrl+alt+f1++ to ++ctrl+alt+f12++ on Linux | The operating system keeps them |
| A key without ++ctrl++, ++alt++ or ++cmd++, other than ++f1++ to ++f12++ | It would type text |
| A [fixed shortcut](#fixed-shortcuts) | korTTY keeps it for its command |

**Show Menu Bar** cannot lose its shortcut: with the menu bar hidden it is the keyboard's way back to the menus. It can get another one.

## Fixed shortcuts

A few shortcuts are listed in grey with the status **Fixed** and cannot be changed, because more than the menu handles them: **Cut**, **Copy** and **Paste** are also the clipboard keys of the terminal and of every text field, **Previous Prompt** and **Next Prompt** are keys of every terminal pane, and the zoom keys and ++f12++ (**Fullscreen**) follow your keyboard layout. **Next Tab** and **Previous Tab** (++ctrl+tab++ and ++ctrl+shift+tab++) and the tab jump keys (++cmd+1++ to ++cmd+9++ on macOS, ++ctrl+1++ to ++ctrl+9++ on Windows and Linux) have no menu item and are fixed too. See [Keyboard shortcuts](../keyboard-shortcuts.md) for what each of them does.

## Conflicts

A shortcut that another command already uses is taken, so you can swap the shortcuts of two commands in two steps. Until each shortcut is used by one command only, both rows show **Also used by …** in red, a line above the list names the shared shortcuts, and the **Keyboard** tab shows a red number. **Save** then keeps the dialog open and shows the Keyboard tab with the first conflict selected: change or remove one shortcut of each pair to save.

## Shortcuts from another computer or version

A shortcut is stored for every platform at once: one you record with ++cmd++ on a Mac is the same shortcut with ++ctrl++ on Windows and Linux. When a settings file holds a shortcut that this computer refuses, such as ++cmd+l++ from a Mac, which is ++ctrl+l++ and belongs to the shell on Linux, the command's row shows **Not used here** and the reason, the command keeps its default shortcut here, and the stored shortcut stays in the file for the Mac. Shortcuts of commands that this version of korTTY does not have, from a newer version, are kept as well, and a note above the list names them. A settings file from a version without this tab opens with every default.

In `global-settings.xml` each changed command is one entry such as `<binding>menu.view.commandPalette=Shortcut+Alt+P</binding>`, or `=none` for a removed shortcut, inside `<keyBindingOverrides>`. The name before `=` is the command's id; `Shortcut` means ++cmd++ on macOS and ++ctrl++ on Windows and Linux, while `Ctrl` is the Control key itself on every platform. An entry korTTY cannot read is ignored.

!!! note
    The AI completion shortcut of the snippet editor is not on this tab; set it on the [Snippet Editor](snippet-editor/index.md) tab.
