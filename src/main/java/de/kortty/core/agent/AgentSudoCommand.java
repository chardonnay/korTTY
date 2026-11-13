package de.kortty.core.agent;

import de.kortty.core.remote.RemoteShell;

/** Validates sudo before the approved shell command can access the execution channel. */
public final class AgentSudoCommand {

    private static final String SCRIPT = """
        set +x
        sudo_executable=$1
        if [ -z "$sudo_executable" ]; then
            for candidate in /usr/bin/sudo /bin/sudo /usr/local/bin/sudo; do
                if [ -x "$candidate" ]; then
                    sudo_executable=$candidate
                    break
                fi
            done
        fi
        if [ -z "$sudo_executable" ]; then
            printf '%s\\n' 'korTTY could not find sudo in a system directory.' >&2
            exit 127
        fi

        exec 4<&0
        (exec "$sudo_executable" -S -p "$2" -v) <&4 4<&- &
        validation_pid=$!
        trap 'kill "$validation_pid" 2>/dev/null; exit 1' HUP INT TERM
        exec </dev/null 4<&-
        printf '%s' "$3" >&2
        wait "$validation_pid" || exit $?
        trap - HUP INT TERM
        printf '%s' "$4" >&2

        # Keep this shell as the parent of sudo -n when sudoers uses a PPID timestamp.
        # The approved command keeps its original run-as user, arguments and redirections.
        planned_command=$5
        shift 5
        unset validation_pid sudo_executable
        eval "$planned_command"
        status=$?
        exit "$status"
        """;

    private AgentSudoCommand() {
    }

    public static String wrap(String command, AgentSudoAuthentication authentication) {
        return wrap(command, authentication, "");
    }

    static String wrap(String command, AgentSudoAuthentication authentication, String sudoExecutable) {
        return "exec /bin/sh -c " + RemoteShell.quote(SCRIPT) + " kortty-sudo "
            + RemoteShell.quote(sudoExecutable) + " "
            + RemoteShell.quote(authentication.promptMarker()) + " "
            + RemoteShell.quote(authentication.armedMarker()) + " "
            + RemoteShell.quote(authentication.readyMarker()) + " "
            + RemoteShell.quote(command);
    }
}
