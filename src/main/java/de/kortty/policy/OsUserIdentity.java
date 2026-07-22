package de.kortty.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link PolicyIdentity} backed by the operating system: the login name from {@code user.name} and
 * the group memberships from {@code id -Gn} (Unix/macOS) or {@code whoami /groups /fo csv}
 * (Windows). On domain-joined Windows machines the reported groups include AD groups, which is how
 * policy rules can target directory groups without an LDAP client.
 *
 * <p>Group membership is determined <b>once</b>, lazily on first use, and cached. The cost — a
 * single short-lived subprocess, time-boxed to {@link #GROUP_LOOKUP_TIMEOUT_SECONDS}s and drained
 * on a daemon thread so a stuck child can never block past the timeout — is only incurred by
 * policies that actually reference groups a user isn't a TOML member of. A policy with no group
 * rules never spawns a process. Any failure or timeout degrades to an empty set, so the user then
 * matches only TOML-defined groups and user rules (fail-closed for group-scoped relaxations).
 */
public final class OsUserIdentity implements PolicyIdentity {

    private static final Logger logger = LoggerFactory.getLogger(OsUserIdentity.class);
    private static final int GROUP_LOOKUP_TIMEOUT_SECONDS = 3;

    private final String userName;
    private volatile Set<String> osGroups;

    public OsUserIdentity() {
        this(System.getProperty("user.name", ""));
    }

    OsUserIdentity(String rawUserName) {
        this.userName = rawUserName == null ? "" : rawUserName.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public String userName() {
        return userName;
    }

    @Override
    public Set<String> osGroups() {
        Set<String> cached = osGroups;
        if (cached == null) {
            synchronized (this) {
                cached = osGroups;
                if (cached == null) {
                    cached = lookupOsGroups();
                    osGroups = cached;
                }
            }
        }
        return cached;
    }

    private static Set<String> lookupOsGroups() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] command = windows
            ? new String[] {"whoami", "/groups", "/fo", "csv", "/nh"}
            : new String[] {"id", "-Gn"};
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // Drain stdout on a daemon thread. Reading inline would block until the child closes
            // its stream, so a child that hangs with stdout open would wait forever and defeat the
            // timeout below; draining separately lets waitFor() govern the total wait.
            StringBuilder output = new StringBuilder();
            Process reading = process;
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(reading.getInputStream(), Charset.defaultCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // Stream closed on destroy/timeout — whatever was captured is used as-is.
                }
            }, "os-group-lookup-drain");
            drain.setDaemon(true);
            drain.start();

            if (!process.waitFor(GROUP_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warn("OS group lookup timed out; policy group rules match TOML groups only");
                return Set.of();
            }
            if (process.exitValue() != 0) {
                logger.warn("OS group lookup exited with {}; policy group rules match TOML groups only",
                    process.exitValue());
                return Set.of();
            }
            drain.join(TimeUnit.SECONDS.toMillis(1));  // let the drain flush the (tiny) buffered output
            synchronized (output) {
                return windows ? parseWindowsGroups(output.toString()) : parseUnixGroups(output.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return Set.of();
        } catch (Exception e) {
            logger.warn("OS group lookup failed; policy group rules match TOML groups only", e);
            return Set.of();
        }
    }

    /** Parses `id -Gn` output: one line of space-separated group names. */
    static Set<String> parseUnixGroups(String output) {
        Set<String> groups = new HashSet<>();
        for (String token : output.trim().split("\\s+")) {
            if (!token.isBlank()) {
                groups.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(groups);
    }

    /**
     * Parses `whoami /groups /fo csv /nh` output: one CSV row per group, first column is the group
     * name (often {@code DOMAIN\Group}). Each group is added both fully qualified and as its bare
     * name so rules can use either form.
     */
    static Set<String> parseWindowsGroups(String output) {
        Set<String> groups = new HashSet<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("\"")) {
                continue;
            }
            int close = trimmed.indexOf('"', 1);
            if (close <= 1) {
                continue;
            }
            String qualified = trimmed.substring(1, close).trim().toLowerCase(Locale.ROOT);
            if (qualified.isEmpty()) {
                continue;
            }
            groups.add(qualified);
            int backslash = qualified.lastIndexOf('\\');
            if (backslash >= 0 && backslash < qualified.length() - 1) {
                groups.add(qualified.substring(backslash + 1));
            }
        }
        return Set.copyOf(groups);
    }
}
