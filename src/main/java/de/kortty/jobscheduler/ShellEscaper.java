package de.kortty.jobscheduler;

import de.kortty.core.remote.RemoteShell;

/** JobScheduler's historic quoting entry point; delegates to {@link RemoteShell}. */
final class ShellEscaper {

    private ShellEscaper() {
    }

    static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return RemoteShell.quote(value);
    }
}
