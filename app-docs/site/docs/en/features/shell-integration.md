---
title: Shell integration
---

# Shell integration

With shell integration, the shell tells korTTY where each prompt starts, where the command you type starts, where its output starts and how the command ended. It does so with invisible OSC 133 marks, the same ones iTerm2, WezTerm, kitty and VS Code read, which a few lines in the shell's startup file add. korTTY uses them to jump between the prompts of a long session with one key, and to select or copy what the last command printed.

Without the marks nothing changes: the keys reach the shell as before, and the terminal looks and behaves as it always did.

## Jumping between prompts

| Command | Keys | Where |
| --- | --- | --- |
| **Previous Prompt** | ++cmd+shift+up++ on macOS, ++ctrl+shift+up++ on Windows and Linux | *Edit* menu and the terminal's right-click menu |
| **Next Prompt** | ++cmd+shift+down++ on macOS, ++ctrl+shift+down++ on Windows and Linux | *Edit* menu and the terminal's right-click menu |

A jump scrolls the pane so that the prompt shows at the top of the view, with the command and its output below it.

- At the bottom of the scrollback, **Previous Prompt** goes to the prompt of the command before the one you are typing, and each further press goes one command further back.
- After you scrolled by hand, a jump starts from the top line you see: **Previous Prompt** goes to the closest prompt above it and **Next Prompt** to the closest one below it, so a prompt you can already see is not skipped.
- A prompt on the last screen cannot move to the top, because the view cannot scroll past the bottom. The view then stays where it is, and the next press continues from that prompt.
- **Next Prompt** after the last prompt goes back to the bottom. **Previous Prompt** above the first prompt does nothing.
- The keys act in the pane that has the keyboard focus, and each pane of a split tab has its own marks. *Edit → Previous Prompt* and *Edit → Next Prompt* act in the focused pane of the selected tab, the right-click menu in the pane you right-clicked. When the *Edit* menu cannot jump, the status bar says why.

The keys are korTTY's only while the pane has prompt marks and shows its normal screen. In a pane without marks, and while a full-screen program such as `vim`, `less`, `htop` or `tmux` runs, they reach the program exactly as before: on Windows and Linux as `ESC [ 1 ; 6 A` and `ESC [ 1 ; 6 B`. With [broadcast mode](terminal.md#broadcast-mode) on, a jump acts only in the pane you pressed the key in; when the key goes to the program instead, broadcast mode sends it to the other panes like any other arrow key. Windows Terminal and GNOME Terminal scroll by one line with ++ctrl+shift+up++ and ++ctrl+shift+down++; in korTTY that is ++ctrl+up++ and ++ctrl+down++ (++cmd+up++ and ++cmd+down++ on macOS).

### The right-click menu

A pane with prompt marks has **Previous Prompt**, **Next Prompt**, **Select Last Output** and **Copy Last Output** in its right-click menu, all greyed out while a full-screen program runs, and the last two also until a command finished. A pane whose shell sends no marks shows **Set Up Shell Integration…** instead, which opens this page at [Setting it up](#setting-it-up). Neither shows while shell integration is switched off, or for a connection whose terminal emulation cannot carry the marks (Wyse, TeleVideo, HP, IBM 3270 and 5250, PETSCII).

## Selecting and copying a command's output

| Command | Where |
| --- | --- |
| **Select Last Output** | *Edit* menu and the terminal's right-click menu |
| **Copy Last Output** | *Edit* menu and the terminal's right-click menu |

**Select Last Output** selects everything the last command printed, as if you had dragged over it with the mouse: *Edit → Copy* then copies it, and with **Copy selection to clipboard automatically** in *Settings → Terminal* it is copied at once. **Copy Last Output** puts the output on the clipboard straight away and leaves the selection you have as it is. Neither has a key of its own.

- The output runs from the line below the command line to where the next prompt starts; the prompt and the command line are not part of it. The copy has no line break at its end, and empty lines at its end are left out. Output without a line break at its end, such as that of `printf done`, ends where the next prompt starts on the same line.
- The last command is the newest one that finished. While a command such as `tail -f` still runs, both take the output of the command before it, and a prompt where you pressed ++enter++ without a command does not count.
- *Edit → Select Last Output* and *Edit → Copy Last Output* act in the focused pane of the selected tab, the right-click menu in the pane you right-clicked. The status bar says what was selected or copied, or why nothing was: no command has finished in the pane yet, the command printed nothing, a full-screen program runs in the pane, or the shell sends no marks.
- When the output is longer than the scrollback holds, its first lines are already gone: both then take what is left, from the oldest line in the scrollback, and the status bar says that the start is missing. Raise **Scrollback** in *Settings → Terminal* to keep longer outputs whole.
- Copies go through korTTY's clipboard, so the enterprise policy's [internal clipboard mode](../reference/enterprise-policy.md#internal-clipboard-mode) keeps them inside korTTY.

Both need the marks for the start of the output and the end of the command (OSC 133 C and D), which korTTY's snippets send. A shell set up some other way that marks only its prompts gets prompt jumps, but the status bar then says that no command has finished.

## Setting it up

Add the snippet for your shell to its startup file on every computer whose shell you want marked: on each server you connect to with SSH, and on your own computer for local shell tabs. korTTY never changes files on a server and never types anything into an SSH session to set this up. Open a new shell, or reconnect the tab, after adding the snippet.

Each snippet:

- runs only in an interactive shell whose `TERM` is not `dumb`, so `scp`, `rsync` and scripts that read the startup file are not affected;
- loads only once, even when the startup file is read twice;
- keeps your prompt and the `PROMPT_COMMAND`, `precmd` or event hooks you already have;
- also reports the shell's working directory with OSC 7, which korTTY uses as the session's current directory, for example for relative file paths and **Open in Snippet Editor**.

To check that it works, run a few commands and press ++cmd+shift+up++ (++ctrl+shift+up++ on Windows and Linux), or right-click the terminal: **Previous Prompt** instead of **Set Up Shell Integration…** means the marks arrive.

### bash

For bash 4.4 or later; in an older bash, such as the bash 3.2 that macOS ships as `/bin/bash`, it does nothing. Save it on the server as `~/.kortty-shell-integration.bash` and add `source ~/.kortty-shell-integration.bash` as the last line of `~/.bashrc`, or paste it at the end of `~/.bashrc`. It has to come after anything else that sets `PROMPT_COMMAND` or `PS1`, such as starship, oh-my-bash or liquidprompt.

```bash
# korTTY shell integration for bash 4.4 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Add it at the end of ~/.bashrc, or save it as ~/.kortty-shell-integration.bash and add
#   source ~/.kortty-shell-integration.bash
# at the end of ~/.bashrc. It does nothing in a non-interactive shell, with TERM=dumb, in an older
# bash, or when it was already loaded.
if [[ $- == *i* && ${TERM:-dumb} != dumb && -z ${__kortty_si_loaded-} ]] &&
    (( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 4) )); then
    __kortty_si_loaded=1

    # OSC 7: the working directory as a file: URL, percent-encoded byte by byte.
    __kortty_si_cwd() {
        local LC_ALL=C path=$PWD url= char hex i
        for (( i = 0; i < ${#path}; i++ )); do
            char=${path:i:1}
            if [[ $char == [-/._~A-Za-z0-9] ]]; then
                url+=$char
            else
                printf -v hex '%02X' "'$char"
                url+=%${hex: -2}
            fi
        done
        printf '\e]7;file://%s%s\a' "${HOSTNAME-}" "$url"
    }

    # Runs first in PROMPT_COMMAND, while $? still holds the command's exit status: D, then OSC 7.
    __kortty_si_precmd() {
        local exit_code=$?
        printf '\e]133;D;%s\a' "$exit_code"
        __kortty_si_cwd
    }

    # Runs last in PROMPT_COMMAND: A at the start of the prompt and B at its end, put back whenever
    # a theme rewrote PS1.
    __kortty_si_prompt() {
        [[ $PS1 == *'\e]133;A\a'* ]] || PS1='\[\e]133;A\a\]'$PS1
        [[ $PS1 == *'\e]133;B\a'* ]] || PS1=$PS1'\[\e]133;B\a\]'
    }

    # C: bash prints PS0 after it read a command line, right before the command runs.
    [[ ${PS0-} == *'\e]133;C\a'* ]] || PS0=${PS0-}'\e]133;C\a'

    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a'* ]]; then
        PROMPT_COMMAND=(__kortty_si_precmd "${PROMPT_COMMAND[@]}" __kortty_si_prompt)
    else
        PROMPT_COMMAND=__kortty_si_precmd${PROMPT_COMMAND:+$'\n'$PROMPT_COMMAND}$'\n'__kortty_si_prompt
    fi
fi
```

### zsh

For zsh 5.1 or later. Save it as `~/.kortty-shell-integration.zsh` and add `source ~/.kortty-shell-integration.zsh` as the last line of `~/.zshrc`, or paste it at the end of `~/.zshrc`, after oh-my-zsh, prezto or starship. With powerlevel10k, set `POWERLEVEL9K_TERM_SHELL_INTEGRATION=true` in `~/.p10k.zsh` instead: powerlevel10k then sends the marks itself.

```zsh
# korTTY shell integration for zsh 5.1 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Add it at the end of ~/.zshrc, or save it as ~/.kortty-shell-integration.zsh and add
#   source ~/.kortty-shell-integration.zsh
# at the end of ~/.zshrc. It does nothing in a non-interactive shell, with TERM=dumb, or when it
# was already loaded.
if [[ -o interactive && ${TERM:-dumb} != dumb && -z ${__kortty_si_loaded-} ]]; then
    typeset -g __kortty_si_loaded=1

    # OSC 7: the working directory as a file: URL, percent-encoded byte by byte.
    __kortty_si_cwd() {
        emulate -L zsh
        setopt no_multibyte
        local LC_ALL=C
        local url= char hex
        local -i i
        for (( i = 1; i <= ${#PWD}; i++ )); do
            char=${PWD[i]}
            if [[ $char == [-/._~A-Za-z0-9] ]]; then
                url+=$char
            else
                printf -v hex '%02X' "'$char"
                url+=%${hex[-2,-1]}
            fi
        done
        printf '\e]7;file://%s%s\a' "${HOST-}" "$url"
    }

    # The first precmd hook, while $? still holds the command's exit status: D, then OSC 7.
    __kortty_si_precmd() {
        local exit_code=$?
        printf '\e]133;D;%s\a' "$exit_code"
        __kortty_si_cwd
    }

    # The last precmd hook: A at the start of the prompt and B at its end, put back whenever a theme
    # rewrote PS1.
    __kortty_si_prompt() {
        [[ $PS1 == *$'\e]133;A\a'* ]] || PS1=$'%{\e]133;A\a%}'$PS1
        [[ $PS1 == *$'\e]133;B\a'* ]] || PS1=$PS1$'%{\e]133;B\a%}'
    }

    # C: zsh runs the preexec hooks after it read a command line, right before the command runs.
    __kortty_si_preexec() {
        printf '\e]133;C\a'
    }

    typeset -ga precmd_functions preexec_functions
    precmd_functions=(__kortty_si_precmd ${precmd_functions[@]} __kortty_si_prompt)
    preexec_functions+=(__kortty_si_preexec)
fi
```

### fish

For fish 3.1 or later. Save it as `~/.config/fish/conf.d/kortty.fish`; fish reads that folder on its own.

```fish
# korTTY shell integration for fish 3.1 or later: OSC 133 prompt and command marks, OSC 7 directory.
# Save it as ~/.config/fish/conf.d/kortty.fish. It does nothing in a non-interactive shell, with
# TERM=dumb, or when it was already loaded.
if status is-interactive; and test "$TERM" != dumb; and not set -q __kortty_si_loaded
    set -g __kortty_si_loaded 1

    # Before every prompt: OSC 7 with the working directory as a file: URL, then A.
    function __kortty_si_prompt_start --on-event fish_prompt
        printf '\e]7;file://%s%s\a' $hostname (string escape --style=url -- $PWD)
        printf '\e]133;A\a'
    end

    # C right before a command runs, D with its exit status right after it.
    function __kortty_si_preexec --on-event fish_preexec
        printf '\e]133;C\a'
    end
    function __kortty_si_postexec --on-event fish_postexec
        printf '\e]133;D;%s\a' $status
    end

    # B at the end of the prompt.
    if functions -q fish_prompt; and not functions -q __kortty_si_original_prompt
        functions -c fish_prompt __kortty_si_original_prompt
        function fish_prompt
            __kortty_si_original_prompt
            printf '\e]133;B\a'
        end
    end
end
```

## Limits

- **tmux and screen** do not pass the marks on, so a shell inside them has none. Run the shell outside tmux, or set up tmux's own prompt navigation.
- **Mosh** connections never carry them: mosh-server draws the screen itself and drops the marks.
- **Local Windows shells**: the snippets cover bash, zsh and fish, not `cmd.exe` or PowerShell, and whether Windows passes the marks of a shell such as Git Bash or WSL on depends on the Windows version.
- **Resizing**: when a width change rewraps long lines, a jump can land a few lines off for prompts above the rewrapped text, and the output that **Select Last Output** and **Copy Last Output** take can be off by as many lines.
- **Nested ssh**: OSC 7 names a host, but korTTY ignores it, so when you ssh on from the first server to a second one that also has the snippet, the second server's directory is taken for the first one's.
- **Fake marks**: any program can print OSC 133 marks. The worst a fake mark does is send a jump to the wrong line, or make **Select Last Output** and **Copy Last Output** take other lines of the pane than the last command's output.

The marks exist only in korTTY's memory for as long as the pane is open: they are not saved with a [project](projects.md), a restored project's output has none, and they never leave your computer.

## Switching it off

*Settings → Terminal → Shell integration* has **Use the command marks of shells set up for shell integration (OSC 133)**, on by default. Switched off, korTTY ignores the marks: the prompt keys reach the program, and the right-click menu shows no shell-integration entries. The change applies to open tabs as soon as you save. The marks stay invisible either way, because the terminal never shows OSC 133 sequences. See [Terminal settings](../reference/settings/terminal.md#notes).
