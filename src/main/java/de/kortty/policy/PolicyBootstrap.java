package de.kortty.policy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Locale;
import java.util.Set;

/**
 * Logger-free policy peek for code that runs <b>before</b> logging is configured (the logging
 * bootstrap in {@code KorTTYApplication}'s static initializer). Touching any slf4j logger there
 * would initialize logback before the log directory property is set, so this class — and
 * everything it loads ({@link PolicyLoader}, {@link EffectivePolicy}, the model records) — must
 * stay free of loggers. {@link PolicyLocator} and {@link PolicyManager} have loggers and must not
 * be referenced here; the small locator ladder is therefore mirrored below.
 *
 * <p>The peek resolves with the OS user name but without OS group membership (no process may be
 * spawned this early); {@link PolicyManager#initialize()} re-resolves fully during startup, and
 * the runtime logging re-configuration then corrects any group-scoped difference.
 */
public final class PolicyBootstrap {

    private PolicyBootstrap() {
    }

    /** Best-effort resolved policy for the current user; unrestricted when unavailable. */
    public static EffectivePolicy peekQuietly() {
        try {
            Path file = locateQuietly();
            if (file == null) {
                return EffectivePolicy.unrestricted();
            }
            PolicyLoadResult result = PolicyLoader.load(file);
            if (!result.isValid()) {
                return EffectivePolicy.lockdown();
            }
            return EffectivePolicy.resolve(result.file(), new PolicyIdentity() {
                @Override
                public String userName() {
                    return System.getProperty("user.name", "").trim().toLowerCase(Locale.ROOT);
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
        } catch (Exception e) {
            return EffectivePolicy.unrestricted();
        }
    }

    /** Mirrors {@link PolicyLocator}'s ladder without any logging. */
    private static Path locateQuietly() {
        String jpackageAppPath = System.getProperty("jpackage.app-path", "");
        String override = System.getProperty(PolicyLocator.OVERRIDE_PROPERTY, "");
        if (!override.isBlank() && jpackageAppPath.isBlank()) {
            Path path = Path.of(override);
            return Files.isRegularFile(path) ? path : null;
        }
        try {
            CodeSource source = PolicyBootstrap.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                Path base = Files.isRegularFile(location) ? location.getParent() : location;
                if (base != null) {
                    Path candidate = base.resolve(PolicyLocator.POLICY_DIR_NAME)
                        .resolve(PolicyLocator.POLICY_FILE_NAME);
                    if (Files.isRegularFile(candidate)) {
                        return candidate;
                    }
                    Path parent = base.getParent();
                    if (parent != null) {
                        candidate = parent.resolve(PolicyLocator.POLICY_DIR_NAME)
                            .resolve(PolicyLocator.POLICY_FILE_NAME);
                        if (Files.isRegularFile(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Best effort — the full locator logs its findings later during startup.
        }
        if (jpackageAppPath.isBlank()) {
            Path devCandidate = Path.of(System.getProperty("user.dir", "."),
                "build", "jpackage-input", "libs", PolicyLocator.POLICY_DIR_NAME,
                PolicyLocator.POLICY_FILE_NAME);
            if (Files.isRegularFile(devCandidate)) {
                return devCandidate;
            }
        }
        return null;
    }
}
