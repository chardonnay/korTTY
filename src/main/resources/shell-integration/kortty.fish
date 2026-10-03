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
