package de.kortty.core.remote;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** Checks whether sudo works on a server without a password (NOPASSWD or a cached timestamp). */
public final class SudoProbe {

    static final String COMMAND = "sudo -n true";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private SudoProbe() {
    }

    /**
     * Runs {@code sudo -n true}, which never prompts.
     *
     * @return true when sudo ran the command without asking for a password
     * @throws IOException when the command could not be run at all (for example, no connection)
     */
    public static boolean canRunWithoutPassword(RemoteCommandRunner runner) throws IOException {
        RemoteCommandRunner.Result result = runner.run(COMMAND, Optional.empty(), TIMEOUT, 4096,
            new RemoteCommandCancellation());
        return result.exitCode() == 0;
    }
}
