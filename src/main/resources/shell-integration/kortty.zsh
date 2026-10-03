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
