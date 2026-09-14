package de.kortty.codingagent.desktop;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * macOS Notification Center through {@code /usr/bin/osascript -e 'display notification …'}. Needs
 * no AWT, so it also works from {@code ./gradlew run}. Runs on the notifier executor.
 */
final class MacOsascriptNotifierBackend implements DesktopNotifierBackend {

    private final Function<List<String>, ExternalCommandRunner.Result> runner;

    MacOsascriptNotifierBackend(Function<List<String>, ExternalCommandRunner.Result> runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public void notify(String title, String body) throws IOException {
        ExternalCommandRunner.Result result = runner.apply(
            NotificationCommands.osascript(NotificationCommands.APP_NAME, title, body));
        if (!result.ok()) {
            throw new IOException("osascript exit " + result.exitCode()
                + (result.timedOut() ? " (timed out)" : "")
                + (result.output().isBlank() ? "" : ": " + result.output()));
        }
    }
}
