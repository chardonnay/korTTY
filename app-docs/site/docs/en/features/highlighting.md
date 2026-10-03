---
title: Keyword highlighting
---

# Keyword highlighting

korTTY can color words and patterns in terminal output as it arrives — errors in red, warnings underlined, IP and MAC addresses, interface names and link states on network devices — so the lines that matter stand out while output scrolls by. Highlighting only changes how the text looks: what the server sent, what you copy, the terminal log and what the AI features read stay exactly the same.

Highlighting is off until you switch it on, for one pane with a menu or the shortcut, or for every pane with a [default rule set](#default-rule-set-and-settings). Each terminal pane shows at most one **rule set** at a time, and korTTY ships three built-in sets.

## Built-in rule sets

| Rule set | What it marks |
| --- | --- |
| **Errors and warnings** | `error`, `failed`, `failure`, `fatal`, `critical`, `denied`, `refused`, `panic`, `exception`, `traceback` and `segmentation fault` in bold red; exception class names such as `NullPointerException` or `ValueError` in red; `warning`, `deprecated` and `timed out` underlined in yellow. Words match whole and in any case, so `ERROR:` is marked but `terror` is not. |
| **Network addresses** | IPv4 addresses with an optional prefix length (`10.0.0.1/24`), IPv6 addresses including compressed forms (`fe80::1%eth0`), and MAC addresses with colons, dashes or in Cisco's dotted form (`aabb.ccdd.eeff`), all underlined. Numbers that only look like an address, such as `999.1.1.1`, are left alone. |
| **Network devices** | Link and session states on switches and routers: `down`, `err-disabled`, `notconnect`, `administratively down`, `inactive` and similar in bold red, `up`, `connected`, `established` and `forwarding` in bold green, and interface names as Cisco, Arista and Juniper print them (`Gi0/1`, `TenGigabitEthernet1/0/1`, `Eth1/2`, `xe-0/0/0`, `ae0`) underlined in cyan. |

The built-in sets use the theme's ANSI colors, so they follow your color settings and the colors of each connection. Every rule also makes its text bold or underlined, so a match still stands out for color-blind users and in a monochrome theme.

## Switching highlighting on and off

Highlighting works per pane: in a split tab every pane can show a different set, and a new split starts with the set of the pane it was split from.

- **Keyboard**: ++ctrl+shift+h++ (++cmd+shift+h++ on macOS) switches highlighting on or off for the pane that has the keyboard focus. Switching it back on brings back the set that pane showed last; a pane that never showed one starts with the default rule set, or with **Errors and warnings** when there is no default. The status bar names the set that is now on.
- **View → Highlighting**: **Highlighting On** does the same as the shortcut. Below it, pick **None** or a rule set for the focused pane of the active tab.
- **Terminal context menu**: right-click a pane and open **Highlighting** for the same entries, applied to the pane you clicked. The submenu is there even when terminal effects are switched off.

!!! note
    A set you choose in these menus or with the shortcut applies to the running session only and is not saved: the next tab you open for the same connection starts with the default rule set, which is **None** unless you chose one in the settings. This differs from **View → Terminal Effect**, which stores the effect on the connection.

The shortcut also works while the menu bar is hidden and in terminal-only fullscreen. On Windows and Linux korTTY keeps ++ctrl+shift+h++ for itself, so it does not reach the program in the terminal; plain ++ctrl+h++ still reaches the shell as backspace.

## Default rule set and settings

The **Keyword highlighting** section of *Configuration → Global Settings → Terminal* holds three settings that apply to every pane; see [Terminal settings](../reference/settings/terminal.md#notes) for the details.

| Setting | Default | What it does |
| --- | --- | --- |
| **Highlight keywords in terminal output** | On | The master switch. Off means no pane is highlighted, whatever was chosen anywhere; the highlighting menus are greyed out and the shortcut only shows a note in the status bar. |
| **Also highlight in full-screen programs (vim, less, htop)** | Off | Highlights inside programs that use the alternate screen as well. |
| **Default rule set** | None | The set every pane shows unless you choose another one for that pane. Choose a set here to have highlighting on in every new and open terminal. |

A pane shows the first of these that applies:

1. the set chosen for that pane in a menu or with the shortcut, including **None**;
2. the default rule set;
3. no highlighting.

A rule set that no longer exists is skipped, so the next level decides. Saving the settings moves every open pane to the set it now resolves to, so a new default appears at once in every pane without a choice of its own.

## How highlighting behaves

- New output is highlighted a moment after it appears (about 50 ms), on a background thread, so a fast `cat` or a log tail keeps its speed.
- A match can continue across a soft-wrapped line, and patterns match wide characters such as CJK text and emoji.
- Choosing another set, or **None**, re-colors the screen at once and the scrollback progressively, newest lines first. **None** restores the original colors everywhere.
- Full-screen programs such as `vim`, `less` and `htop` are not highlighted unless you allow it in the [settings](#default-rule-set-and-settings): they redraw constantly and bring their own colors, so highlights would flicker and fight them.
- When output floods in faster than it can be checked, lines more than 10,000 rows above the bottom are left as they are.
- Text a program marks as concealed stays hidden, and links keep their own style.
- Highlights also show on connections whose terminal colors are switched off, and they appear in [terminal recordings](recording.md), because they are part of what the pane shows.
- While the find bar shows results, korTTY waits with re-coloring the scrollback after you switch sets, so the matches you are looking at stay marked. A line that is highlighted while the find bar is open can lose its find mark; search again to bring it back.

## Limits

Each rule gets at most 2 ms per line of output. A rule that runs out of time three times — usually a regular expression that backtracks badly — is switched off for that pane until you choose a set again. A line, soft-wrapped rows included, is checked up to its first 8,192 characters.

## Security and privacy

!!! warning
    A highlight is not a trust signal. The server decides what it prints, so it can print text that matches a rule, just as it can color its own output. Highlighting never changes what is sent or received.

Rule sets stay on your computer, and the log names rules and sets by their ids only, never by their patterns or by the text they matched. If you allowed [anonymous usage statistics](../about/anonymous-data.md), korTTY reports which built-in set was switched on (any set of your own counts only as "custom") and whether that happened from a menu, with the shortcut or through the default rule set, and which of the three settings you changed, with the default rule set again reported only as a built-in set, "custom" or "none" — never patterns, set names or terminal text.
