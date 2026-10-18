package de.kortty.ui.sftp;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * This computer's name for display, such as in the archive dialog's "This computer (name)". It is
 * looked up once in the background: {@link InetAddress#getLocalHost()} can block for seconds on a
 * Mac whose name does not resolve, which must never happen on the FX thread.
 */
public final class LocalHostName {

    private static final CompletableFuture<String> NAME = CompletableFuture.supplyAsync(LocalHostName::resolve);

    private LocalHostName() {
    }

    /** Starts the lookup early, so the name is ready when a dialog first asks. */
    public static void prefetch() {
        // Loading the class started the lookup.
    }

    /** The name once the lookup finished; empty while it runs or when there is none. */
    public static Optional<String> current() {
        return Optional.ofNullable(NAME.getNow(null)).filter(name -> !name.isBlank());
    }

    private static String resolve() {
        String fromEnvironment = System.getenv("COMPUTERNAME");
        if (fromEnvironment == null || fromEnvironment.isBlank()) {
            fromEnvironment = System.getenv("HOSTNAME");
        }
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return displayName(fromEnvironment);
        }
        try {
            return displayName(InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code name} without the {@code .local} suffix macOS adds ("Daniels-MBP.local"). */
    static String displayName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".local")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".local".length());
        }
        return trimmed;
    }
}
