---
title: Terminal notifications
---

# Terminal notifications

A program in a terminal can ask for your attention while you are working somewhere else: a build script rings the bell when it is done, a prompt rings it when it waits for input, a coding agent on a server asks for a notification when it needs your answer, and in a shell set up for [shell integration](shell-integration.md) a long command tells korTTY when it has finished. KorTTY marks the tab such a request comes from, so you see it in the tab bar, and can also show a desktop notification. It never plays a sound.

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

## Long-running commands

In a shell set up for [shell integration](shell-integration.md#setting-it-up), the shell marks when each command starts and when it finishes, with its exit status. When a command that ran at least the minimum runtime, 30 seconds by default, finishes in a tab you are not looking at, korTTY marks that tab with 🔔 and shows a desktop notification, for example `korTTY · web-01` with the text *Command failed (exit 1) after 2 min 14 sec.* Start a build or an upgrade, switch to another tab or application, and you hear from korTTY when it is done.

- The runtime counts from the moment you pressed ++enter++ to the moment the command finished, without the time you spent typing it. A command that ends with exit status 0 says *Command finished*, any other status says *Command failed*, and a shell that reports no status gets *Command finished after …* without one.
- The notification never contains the command itself, nor anything it printed: command lines can contain passwords and tokens, and a notification can appear on the lock screen. Pointing at the marked tab shows the same text in its tooltip.
- A command that finishes in the tab you are looking at leads to nothing, and so does a command shorter than the minimum runtime. The tab gets its mark even with the notification switched off.
- A tab shows at most one such notification every 10 seconds. When [broadcast](terminal.md#split-screen-with-broadcast) typed the same command into several panes of a tab and they finish close together, you get one notification, and the tab stays marked until you look at it.
- Commands that korTTY's [AI Agent](ai-assistant.md#ai-agent-and-ai-planning) runs in a pane neither mark the tab nor notify: the agent's run reports its own commands in its activity panel.
- A [coding agent](coding-agents.md) such as Claude Code is itself a command to the shell, so when it exits in a tab you are not looking at, that is reported like any other command.
- Without shell integration nothing changes: korTTY does not guess from pauses in the output when a command has finished. Shells without the marks, `tmux`, `screen` and mosh connections get no long-command notifications; see the [limits of shell integration](shell-integration.md#limits).

*Settings → Terminal → Notifications* has **Desktop notification when a long-running command finishes in a tab you are not looking at**, on by default, and **Minimum command runtime:**, from 1 to 3,600 seconds. Both are greyed out while shell integration is switched off, and a change applies to the open tabs as soon as you save. See [Terminal settings](../reference/settings/terminal.md#notes).

## Notifications from programs

A program can ask the terminal for a desktop notification with an escape sequence: `OSC 9` (the iTerm2 form, `ESC ] 9 ; text BEL`) or `OSC 777` (the urxvt and foot form, `ESC ] 777 ; notify ; title ; body BEL`). Coding agents such as Claude Code or Codex running on a server can use it to say that they wait for your answer or have finished, and so can your own scripts. Many agents and tools have a setting that sends their notifications this way, often called the iTerm2 style; korTTY understands both forms in local shells and SSH sessions alike, with nothing to install on the server. To try it, run this and switch to another tab within five seconds:

```bash
sleep 5; printf '\e]777;notify;%s;%s\a' 'Backup' 'Finished without errors'
```

When such a request comes from a tab you are not looking at, korTTY marks the tab with 🔔, and pointing at the tab shows *A program in this tab sent a notification:* with the text in its tooltip. A desktop notification titled `korTTY · ` and the name of the tab shows the text below that title, for example *Backup: Finished without errors*, so it always says which tab it came from and never passes for a message of another application.

- The program chooses every character of the text, so korTTY cleans it first: control characters, line breaks and the invisible characters that change the reading direction are removed, the title is cut to 80 characters and the text to 200. The terminal never shows the sequence itself.
- A pane shows at most one such notification every 5 seconds; what a program asks for in that time only keeps the mark on the tab and is dropped, so a program that prints notifications in a loop cannot flood your desktop.
- A request in the tab you are looking at leads to nothing.
- A pane in which korTTY detected a [coding agent](coding-agents.md) gets no notification of this kind while **Desktop notification when a coding agent needs a decision or finishes while you are not looking at its pane** is on, because the agent's own notification already says it. korTTY detects agents in local shell tabs only, so an agent on a server always notifies through its own request.
- `OSC 9` with a number first, such as the `ESC ] 9 ; 4 ; 1 ; 50 BEL` progress report of ConEmu and Windows Terminal, is no notification and is ignored. Other `OSC 777` commands are no notifications either and are left alone.
- It needs no [shell integration](shell-integration.md), and switching shell integration off does not stop it.
- Inside `tmux` or `screen` the requests usually do not arrive: the multiplexer does not pass them on, and korTTY does not unwrap tmux's passthrough sequences. Mosh connections never carry them.
- korTTY's own log never records the text; the tab's tooltip and the notification are the only places korTTY shows it. A [terminal log](terminal.md#terminal-logging) or [session journal](session-journal.md) of the pane records the output as it arrived and can therefore contain it.

*Settings → Terminal → Notifications* has **Desktop notification when a program in a tab you are not looking at asks for one (OSC 9, OSC 777)**, on by default. Switched off, a request only marks the tab. A change applies to the open tabs as soon as you save. See [Terminal settings](../reference/settings/terminal.md#notes).

!!! note "Privacy"
    A desktop notification shows the tab's name, which can be a server name, and depending on your operating system's settings it can appear on the lock screen; a program's notification also shows the text the program sent. Turn off the desktop notifications you do not want, or the notifications for korTTY in the operating system; the mark on the tab stays inside korTTY.
