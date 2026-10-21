---
title: AI agent designs
---

# AI agent designs

*Plugins → AI Agent Design* decides how the runs of the terminal [AI agent](ai-tools.md) are shown: as a block in the terminal itself, in a slim bar below it, in the classic activity panel, or in a combination of these. A **combination** picks one design for each role, and you can set one combination as the default and give single connections a different one.

![AI Agent Design with the standard combination: the live preview shows a sample run as a block in the terminal, waiting for an approval, with the metro line and the approval row below](../assets/screenshots/plugins/ai-agent-design.png)

Open it from *Plugins → AI Agent Design* or *View → AI Agent Panel → Design...*. With *open tool windows as tabs* turned on it opens as a tab of the main window. An organisation policy that does not allow plugins locks both entries.

## Roles and designs

The table lists every design with its number, grouped by role. A tick in **Active** chooses the design for its role in the shown combination, so each role always has exactly one tick.

| Role | What it decides | Designs you can choose |
| --- | --- | --- |
| History | Where the steps of a run are listed | **Blocks in the terminal** (03), **Classic panel** |
| Status | A compact display of where the run stands, next to the history | **Metro line** (08), **No status display** |
| Approval | Where approval and password requests are answered | **In the history** |
| Arrangement | Where the parts are placed | **Fixed** |

The other numbered designs are listed with *(coming later)* and cannot be ticked yet. A combination stored by a newer korTTY that names one of them uses the standard combination's design for that role instead.

## Combinations

| Combination | History | Status |
| --- | --- | --- |
| Standard (blocks + metro line) | Blocks in the terminal | Metro line |
| Classic | Classic panel | No status display |

**Standard** is the default for every connection that does not pick its own combination; the default is marked with ★ in the list. The two built-in combinations cannot be changed: ticking another design while one of them is shown creates your own copy with that change. **New** starts a copy of the standard combination, **Duplicate** copies the shown one, **Rename** and **Delete** work on your own combinations, and **Use as Default** makes the shown combination the default. Deleting the default makes **Standard** the default again. Every change is saved at once.

## Preview

The preview is a real terminal pane with a short shell session in which a sample run plays: the agent inspects the session, analyses, runs two commands, asks to shrink the journal, gets the answer **1** and finishes with its answer. It uses your default terminal colours and font, a little smaller. Selecting a row in the table shows that design in the current combination without saving it; **Replay** starts the run again.

![The preview after the sample run: the block in the terminal collapsed to the answer, the metro line with every station reached](../assets/screenshots/plugins/ai-agent-design-finished.png)

## Blocks in the terminal (03)

The run appears as a block directly above the shell's prompt line. Its header names the agent and your question, and shows the elapsed time and the reported tokens; below it there is one row per step with its result, and the running step turns a small spinner. korTTY writes the block into the terminal itself and sends nothing to the server, and the prompt line where you type is never rewritten, so you can keep typing the next command while the agent works.

- ++ctrl+r++ shows or hides the details of the steps, ++esc++ or ++ctrl+c++ cancels the run.
- An approval lists the planned commands with the keys **1** (allow once), **2** (allow always) and **3** (cancel); while it is open these digits answer it and do not reach the shell.
- When the run ends, the block collapses to a header with ✓ (or *cancelled*, *failed*), the number of steps, the time and the tokens, followed by the answer. The block then stays in the scrollback like any other output and goes into the session journal's capture.
- The answer is part of the block, so the run dialog's **Show final answer in the terminal too** option only applies to combinations without blocks.
- While a full-screen program such as `less` or `vim` is open the block waits and catches up afterwards. A block that scrolled out of the screen during the run continues in a new block above the prompt.

## Metro line (08)

A slim bar below the terminal draws the run as a metro line: one station per command, analysis and question, and an **Answer** station at the end. Reached stations are filled, the current one pulses, a station waiting for your input glows amber, and a light travels along the line as the run moves on; when there are more stations than fit, the oldest are summed up as *+N*. The bar also shows the question, the tokens and the time, and has buttons to pause, cancel and hide it.

The bar fades out a few seconds after the last run ended; the delay is set in the dialog. *View → AI Agent Panel → Show or Hide Status Bar* brings it back or hides it.

## Approvals and passwords

With blocks in the terminal, an approval or a password request also opens a row below the terminal. For an approval it shows the commands and the buttons **1 Allow Once**, **2 Allow Always** and **3 Cancel**. For a password it has a masked field, **Use for this SSH session** and **OK**; ++enter++ submits and ++esc++ cancels. The classic panel keeps its own prompts.

## Options

| Option | Effect |
| --- | --- |
| Hide the status bar after a run after (seconds, 0 = keep) | How long the metro line stays after the last run ended; 0 keeps it until you hide it. Default: 6 seconds. |
| Reduce animations | Turns off the pulsing station, the moving light and the spinner in the block. |

## A design per connection

The **AI** tab of the [connection editor](connections.md#creating-editing-a-connection) has an **AI agent design** list: **Use the default** (with the current default's name) or any combination. The choice applies from the next run on that connection. Because your own combinations live in this computer's settings, the choice is not part of an exported connection; a connection whose combination was deleted shows **Stored combination (currently unavailable)** and uses the default.

## Colours

The designs take their colours from the **Terminal AI agent panel** colours of the connection's terminal theme, which the theme editor on the [Themes tab](../reference/settings/appearance.md#themes-tab) shows. A theme whose cursor is white or grey gets a blue accent, a lighter one on dark themes and a darker one on light themes; the panel background follows the terminal's own background with only a faint tint. Agent panel colours that korTTY derived for a theme before the blue accent are derived again once; colours you picked yourself stay. Green is kept for finished steps.

## Docked to the side

*View → AI Agent Panel → Dock Left* or *Dock Right* shows the classic panel in the side dock; blocks keep appearing in the terminal, and the metro line moves into the dock with the panel.

## Stored settings

| File | Element | Meaning |
| --- | --- | --- |
| `global-settings.xml` | `aiAgentDesignDefaultId` | The default combination; absent for **Standard** |
| `global-settings.xml` | `aiAgentDesignCombinations` | Your own combinations, one `combination` element each |
| `global-settings.xml` | `aiAgentDesignAutoHideSeconds` | The status bar delay; absent for 6 seconds |
| `global-settings.xml` | `aiAgentDesignReducedAnimations` | Reduce animations |
| `connections.xml` | `aiAgentDesignId` | A connection's own combination; absent for the default |
