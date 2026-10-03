---
title: Terminal notifications
---

# Terminal notifications

A program in a terminal can ask for your attention while you are working somewhere else: a build script rings the bell when it is done, a prompt rings it when it waits for input. KorTTY marks the tab such a request comes from, so you see it in the tab bar, and can also show a desktop notification. It never plays a sound.

## When you are looking at a tab

KorTTY only asks for your attention in tabs you are not looking at. A tab counts as seen while it is the selected tab of the window in front, that is the window that has the keyboard focus and is not minimised. Which pane of a tab with [split panes](terminal.md#split-screen-with-broadcast) has the focus does not matter: the tab is seen as a whole. A tab that is not selected, the tabs of a korTTY window behind another one, and every tab while another application is in front are not seen.

## The terminal bell

Programs ring the terminal bell with the BEL control character (++ctrl+g++): `bash` and `zsh` ring it when a ++tab++ completion finds nothing, `less` and `vim` when you try to move past the end of a file, and a script can ring it with `printf '\a'` when a long job ends. The bell works in local shells and SSH sessions alike, in every terminal emulation korTTY offers, and needs no setup on the server.

### Bell mark on the tab

When the bell rings in a tab you are not looking at, the tab shows 🔔 in its title, after the coding-agent glyph and before the `[group]` prefix, for example `⚡ 🔔 [Ops] web-01`. Pointing at the tab shows *The bell rang while you were not looking at this tab.* in its tooltip, so the mark is also explained in words.

The mark goes away as soon as you look at the tab: select it, or bring its window to the front. A bell in the tab you are looking at leaves no mark. The mark is always on and makes no sound, so it has no setting of its own. Many bells in quick succession, for example from `cat` on a binary file, count as one.

### Desktop notification for the bell

With **Desktop notification when the bell rings in a tab you are not looking at** switched on in *Settings → Terminal → Notifications* (see [Terminal settings](../reference/settings/terminal.md#notes)), a bell in a tab you are not looking at also shows a desktop notification through the operating system's notification service: Notification Center on macOS, a tray balloon on Windows and `notify-send` on Linux.

- The notification's title is `korTTY · ` and the name of the tab, its text says that a program rang the bell. It never contains terminal output. A name the shell set for the tab is cleaned of control and bidi characters first.
- A pane shows at most one bell notification every 10 seconds; further bells in that time only keep the mark on the tab. Each pane of a split tab counts on its own.
- The setting is off by default, because shells ring the bell on every failed ++tab++ completion. A change applies to the open tabs as soon as you save.
- A pane in which korTTY detected a [coding agent](coding-agents.md) gets no bell notification while **Desktop notification when a coding agent needs a decision or finishes while you are not looking at its pane** is on: agents ring the bell when they wait for you, and their own notification already says so, with more detail. The tab still gets its mark.
- Clicking the notification does not bring the tab to the front; the notification services korTTY uses cannot report the click back. Look for the 🔔 in the tab bar instead.

!!! note "Privacy"
    A desktop notification shows the tab's name, which can be a server name, and depending on your operating system's settings it can appear on the lock screen. Leave the setting off if that is a concern; the mark on the tab stays inside korTTY.
