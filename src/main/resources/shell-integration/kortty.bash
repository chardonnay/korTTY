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
    # It returns that status again, so the PROMPT_COMMAND hooks after it still see it.
    __kortty_si_precmd() {
        local exit_code=$?
        printf '\e]133;D;%s\a' "$exit_code"
        __kortty_si_cwd
        return "$exit_code"
    }

    # Runs last in PROMPT_COMMAND: A at the start of the prompt and B at its end, put back whenever
    # a theme rewrote PS1. It leaves $? as it found it, for a prompt that shows it.
    __kortty_si_prompt() {
        local exit_code=$?
        [[ $PS1 == *'\e]133;A\a'* ]] || PS1='\[\e]133;A\a\]'$PS1
        [[ $PS1 == *'\e]133;B\a'* ]] || PS1=$PS1'\[\e]133;B\a\]'
        return "$exit_code"
    }

    # C: bash prints PS0 after it read a command line, right before the command runs.
    [[ ${PS0-} == *'\e]133;C\a'* ]] || PS0=${PS0-}'\e]133;C\a'

    if [[ $(declare -p PROMPT_COMMAND 2>/dev/null) == 'declare -a'* ]]; then
        PROMPT_COMMAND=(__kortty_si_precmd "${PROMPT_COMMAND[@]}" __kortty_si_prompt)
    else
        PROMPT_COMMAND=__kortty_si_precmd${PROMPT_COMMAND:+$'\n'$PROMPT_COMMAND}$'\n'__kortty_si_prompt
    fi
fi
