package de.kortty.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared Flatpak environment, host-command, and bundle-install helpers. */
public final class FlatpakSupport {

    public static final String APP_ID = "io.github.chardonnay.korTTY";

    private FlatpakSupport() {
    }

    /** True when this process was launched by Flatpak. */
    public static boolean isRunningInFlatpak() {
        return isFlatpakEnvironment(System.getenv());
    }

    static boolean isFlatpakEnvironment(Map<String, String> environment) {
        String id = environment != null ? environment.get("FLATPAK_ID") : null;
        return id != null && !id.isBlank();
    }

    /**
     * Prefixes a command with {@code flatpak-spawn --host} while preserving only the terminal
     * environment needed by an interactive host shell. The explicit working directory avoids
     * accidentally starting in the sandbox's private home.
     */
    public static List<String> hostCommand(
        List<String> command,
        String workingDirectory,
        Map<String, String> environment
    ) {
        Objects.requireNonNull(command, "command");
        if (!isFlatpakEnvironment(environment)) {
            return List.copyOf(command);
        }
        List<String> wrapped = new ArrayList<>();
        wrapped.add("flatpak-spawn");
        wrapped.add("--host");
        wrapped.add("--watch-bus");
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            wrapped.add("--directory=" + workingDirectory);
        }
        copyEnvironment(wrapped, environment, "TERM");
        copyEnvironment(wrapped, environment, "COLORTERM");
        copyEnvironment(wrapped, environment, "LANG");
        wrapped.addAll(command);
        return List.copyOf(wrapped);
    }

    /** Manual host-terminal command shown after a Flatpak update bundle was downloaded. */
    public static String installCommand(Path bundle) {
        Objects.requireNonNull(bundle, "bundle");
        return "flatpak install --user " + quoteForPosixShell(bundle.toAbsolutePath().normalize().toString());
    }

    private static void copyEnvironment(List<String> command, Map<String, String> environment, String name) {
        String value = environment != null ? environment.get(name) : null;
        if (value != null && !value.isBlank() && value.indexOf('\0') < 0) {
            command.add("--env=" + name + "=" + value);
        }
    }

    private static String quoteForPosixShell(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
