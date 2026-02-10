package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.model.ConnectionSource;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Loads teamwork connections from a Git repository.
 * Expects a file named kortty-teamwork-connections.xml (or connections.xml) in the repo root.
 */
public class GitTeamworkAdapter implements TeamworkConnectionRepository {

    private static final Logger logger = LoggerFactory.getLogger(GitTeamworkAdapter.class);

    /** Filename to look for in the repo root. */
    public static final String CONNECTIONS_FILENAME = "kortty-teamwork-connections.xml";
    public static final String CONNECTIONS_FILENAME_LEGACY = "connections.xml";

    /** Timeout in seconds for reading Git process output. */
    private static final long OUTPUT_READ_TIMEOUT_SECONDS = 60;

    private final Path configDir;
    private final Path reposDir;

    public GitTeamworkAdapter(Path configDir) {
        this.configDir = configDir;
        this.reposDir = configDir.resolve("teamwork-repos");
    }

    @Override
    public TeamworkLoadResult loadConnections(TeamworkSourceConfig source) {
        if (source.getType() != TeamworkSourceType.GIT || source.getLocation() == null) {
            return null;
        }
        Path repoPath = repoPathFor(source.getId());
        String redactedLocation = redactLocation(source.getLocation());
        try {
            ensureClonedOrPulled(source.getLocation(), repoPath);
            Path connectionsFile = findConnectionsFile(repoPath);
            if (connectionsFile == null) {
                logger.warn("No connections file found in repo {}", redactedLocation);
                return new TeamworkLoadResult(List.of(), currentRevision(repoPath));
            }
            XMLConnectionRepository repo = new XMLConnectionRepository(configDir);
            List<ServerConnection> connections = repo.importConnections(connectionsFile);
            String versionToken = currentRevision(repoPath);
            for (ServerConnection c : connections) {
                c.setConnectionSource(ConnectionSource.TEAMWORK);
                c.setTeamworkSourceId(source.getId());
                c.setTeamworkVersionToken(versionToken);
                stripInlineSecrets(c);
            }
            return new TeamworkLoadResult(connections, versionToken);
        } catch (Exception e) {
            logger.error("Failed to load teamwork connections from Git: {}", redactedLocation, e);
            return null;
        }
    }

    private Path repoPathFor(String sourceId) {
        String safe = Pattern.compile("[^a-zA-Z0-9_-]").matcher(sourceId).replaceAll("_");
        String hash = sha256Prefix(sourceId, 8);
        return reposDir.resolve(safe + "-" + hash);
    }

    /** First {@code hexChars} hex chars of SHA-256 of input (UTF-8), for deterministic distinct paths. */
    private static String sha256Prefix(String input, int hexChars) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hexChars);
            int bytesNeeded = (hexChars + 1) / 2;
            for (int i = 0; i < Math.min(bytesNeeded, digest.length); i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.substring(0, Math.min(hexChars, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final Set<String> ALLOWED_GIT_SCHEMES = Set.of("http", "https", "ssh", "git");

    private void ensureClonedOrPulled(String gitUrl, Path repoPath) throws Exception {
        String sanitized = validateAndSanitizeGitUrl(gitUrl);
        if (!Files.exists(repoPath)) {
            Files.createDirectories(reposDir);
            runGit(reposDir, "clone", "--depth", "1", "--", sanitized, repoPath.toString());
        } else {
            String branch = runGitCapture(repoPath, "rev-parse", "--abbrev-ref", "HEAD");
            if (branch != null && !branch.trim().isEmpty()) {
                runGit(repoPath, "fetch", "origin", branch.trim());
                runGit(repoPath, "reset", "--hard", "origin/" + branch.trim());
            } else {
                runGit(repoPath, "pull", "--rebase");
            }
        }
    }

    /**
     * Validates the Git URL to prevent argument injection (e.g. URLs starting with '-' or containing crafted options).
     * Allows only http, https, ssh, and git schemes.
     */
    private static String validateAndSanitizeGitUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IllegalArgumentException("Git URL must not be null or blank");
        }
        String trimmed = gitUrl.trim();
        if (trimmed.startsWith("-")) {
            throw new IllegalArgumentException("Git URL must not start with '-'");
        }
        int schemeDelim = trimmed.indexOf("://");
        if (schemeDelim > 0) {
            String scheme = trimmed.substring(0, schemeDelim).toLowerCase(Locale.ROOT);
            if (!ALLOWED_GIT_SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("Git URL scheme not allowed: " + scheme + ". Use http, https, ssh, or git.");
            }
        }
        return trimmed;
    }

    private Path findConnectionsFile(Path repoPath) {
        Path primary = repoPath.resolve(CONNECTIONS_FILENAME);
        if (Files.isRegularFile(primary)) return primary;
        Path legacy = repoPath.resolve(CONNECTIONS_FILENAME_LEGACY);
        if (Files.isRegularFile(legacy)) return legacy;
        return null;
    }

    private String currentRevision(Path repoPath) {
        try {
            String out = runGitCapture(repoPath, "rev-parse", "HEAD");
            return out != null ? out.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void runGit(Path cwd, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(prependGit(args));
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ExecutorService executor = null;
        Future<String> outputFuture = null;
        try {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "git-output-drain");
                t.setDaemon(true);
                return t;
            });
            outputFuture = executor.submit(() ->
                new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            boolean completed = p.waitFor(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                outputFuture.cancel(true);
                throw new RuntimeException("Git process timed out after " + OUTPUT_READ_TIMEOUT_SECONDS + " seconds");
            }
            int code = p.exitValue();
            String output;
            try {
                output = outputFuture.get(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                outputFuture.cancel(true);
                throw new RuntimeException("Git output read timed out after " + OUTPUT_READ_TIMEOUT_SECONDS + " seconds", e);
            } catch (ExecutionException e) {
                outputFuture.cancel(true);
                Throwable cause = e.getCause();
                throw new RuntimeException("Git output read failed: " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outputFuture.cancel(true);
                throw new RuntimeException("Git output read interrupted", e);
            }
            if (code != 0) {
                throw new RuntimeException("Git failed: " + output);
            }
        } finally {
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private String runGitCapture(Path cwd, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(prependGit(args));
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        ExecutorService executor = null;
        Future<String> outputFuture = null;
        try {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "git-capture-output-drain");
                t.setDaemon(true);
                return t;
            });
            outputFuture = executor.submit(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder all = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (all.length() > 0) all.append('\n');
                        all.append(line);
                    }
                    return all.length() > 0 ? all.toString() : null;
                }
            });
            boolean completed = p.waitFor(OUTPUT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                outputFuture.cancel(true);
                throw new RuntimeException("Git process timed out after " + OUTPUT_READ_TIMEOUT_SECONDS
                    + " seconds (cwd=" + cwd + ", args=" + java.util.Arrays.toString(args) + ")");
            }
            String output;
            try {
                long remaining = Math.max(1, OUTPUT_READ_TIMEOUT_SECONDS);
                output = outputFuture.get(remaining, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                outputFuture.cancel(true);
                throw new RuntimeException("Git output read timed out after " + OUTPUT_READ_TIMEOUT_SECONDS + " seconds", e);
            } catch (ExecutionException e) {
                outputFuture.cancel(true);
                Throwable cause = e.getCause();
                throw new RuntimeException("Git output read failed: " + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                outputFuture.cancel(true);
                throw new RuntimeException("Git output read interrupted", e);
            }
            int code = p.exitValue();
            if (code != 0) {
                throw new RuntimeException("Git failed (exit " + code + "): " + output);
            }
            return output;
        } finally {
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** Teamwork connections must not retain inline secrets from the repo; only credentialId/sshKeyId. */
    private static void stripInlineSecrets(ServerConnection c) {
        c.setEncryptedPassword(null);
        c.setPrivateKeyPath(null);
        c.setPrivateKeyPassphrase(null);
        c.setTemporaryKeyContent(null);
        c.setTemporaryKeyExpirationMinutes(null);
        c.setTemporaryKeyPermanent(false);
    }

    /** Redacts userinfo (user:pass) from a URL so it is safe for logging. */
    private static String redactLocation(String location) {
        if (location == null) return null;
        return location.replaceAll("(://)[^/@]+@", "$1***@");
    }

    private static String[] prependGit(String[] args) {
        String git = "git";
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win")) {
            git = "git.exe";
        }
        String[] out = new String[args.length + 1];
        out[0] = git;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }
}
