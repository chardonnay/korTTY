package de.kortty.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Locates {@code kortty-policy.toml}. The file is only honored from the application's installation
 * directory (which must be admin-writable only) — never from the user's config directory — so a
 * user cannot plant or replace a policy. Mirrors the resolver ladder of
 * {@link de.kortty.core.CodeFormatterService}:
 *
 * <ol>
 *   <li>{@code -Dkortty.policy.file=<path>} — development override, honored <b>only</b> in
 *       non-packaged launches (disabled whenever {@code jpackage.app-path} is set, exactly like
 *       {@code TEST_MODE_KORTTY});</li>
 *   <li>{@code <dir of korTTY jar>/policy/kortty-policy.toml} (the jpackage app directory);</li>
 *   <li>the same in the jar directory's parent;</li>
 *   <li>dev fallback: {@code build/jpackage-input/libs/policy/kortty-policy.toml} under the
 *       working directory.</li>
 * </ol>
 */
public final class PolicyLocator {

    public static final String POLICY_FILE_NAME = "kortty-policy.toml";
    public static final String POLICY_DIR_NAME = "policy";
    public static final String OVERRIDE_PROPERTY = "kortty.policy.file";

    private static final Logger logger = LoggerFactory.getLogger(PolicyLocator.class);

    private PolicyLocator() {
    }

    /** The active policy file, or empty when none is installed. */
    public static Optional<Path> locate() {
        String override = System.getProperty(OVERRIDE_PROPERTY, "");
        if (!override.isBlank()) {
            if (isPackagedBuild()) {
                logger.warn("-D{} is ignored in packaged builds", OVERRIDE_PROPERTY);
            } else {
                Path path = Path.of(override);
                if (Files.isRegularFile(path)) {
                    logger.info("Using development policy override {}", path);
                    return Optional.of(path);
                }
                logger.warn("-D{} points to a missing file: {}", OVERRIDE_PROPERTY, path);
                return Optional.empty();
            }
        }
        for (Path candidate : installCandidates()) {
            if (Files.isRegularFile(candidate)) {
                logger.info("Using policy file {}", candidate);
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Best-effort check whether the current user could modify {@code policyFile} (or its directory).
     * A writable policy defeats the admin-only model; callers log a prominent warning.
     */
    public static boolean isWritableByCurrentUser(Path policyFile) {
        try {
            return Files.isWritable(policyFile) || Files.isWritable(policyFile.getParent());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isPackagedBuild() {
        String jpackageAppPath = System.getProperty("jpackage.app-path");
        return jpackageAppPath != null && !jpackageAppPath.isBlank();
    }

    private static List<Path> installCandidates() {
        List<Path> candidates = new ArrayList<>();
        try {
            CodeSource source = PolicyLocator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                Path base = Files.isRegularFile(location) ? location.getParent() : location;
                if (base != null) {
                    candidates.add(base.resolve(POLICY_DIR_NAME).resolve(POLICY_FILE_NAME));
                    Path parent = base.getParent();
                    if (parent != null) {
                        candidates.add(parent.resolve(POLICY_DIR_NAME).resolve(POLICY_FILE_NAME));
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not derive the install directory from the code source", e);
        }
        if (!isPackagedBuild()) {
            candidates.add(Path.of(System.getProperty("user.dir", "."),
                "build", "jpackage-input", "libs", POLICY_DIR_NAME, POLICY_FILE_NAME));
        }
        return candidates;
    }
}
