package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class JobSchedulerRsyncSupport {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerRsyncSupport.class);
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_READ_WRITE_EXECUTE = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);

    private final KorTTYApplication app;
    private final RsyncToolLocator toolLocator;
    private final RsyncProcessExecutor processExecutor;
    private final RsyncCommandBuilder commandBuilder = new RsyncCommandBuilder();

    JobSchedulerRsyncSupport(KorTTYApplication app) {
        this(app, new RsyncToolLocator(), new DefaultRsyncProcessExecutor());
    }

    JobSchedulerRsyncSupport(
        KorTTYApplication app,
        RsyncToolLocator toolLocator,
        RsyncProcessExecutor processExecutor) {

        this.app = app;
        this.toolLocator = toolLocator;
        this.processExecutor = processExecutor;
    }

    JobExecutionOutcome run(
        ScheduledJob job,
        ServerConnection connection,
        PinnedHostKey pinnedHostKey,
        JobSchedulerRemoteSession.ExternalSshAuthMaterial auth,
        int targetCount,
        JobSchedulerSecretRedactor redactor) throws Exception {

        JobAction action = job.getAction();
        String configuredRsyncBinary = app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings().getJobSchedulerRsyncBinaryPath()
            : null;
        String rsyncBinary = toolLocator.resolveRsync(configuredRsyncBinary);
        String sshBinary = toolLocator.resolveSsh();
        String effectiveTargetRoot = effectiveTargetRoot(action, connection, targetCount);
        if (action.getRsyncDirection() == RsyncDirection.DOWNLOAD) {
            Files.createDirectories(Path.of(effectiveTargetRoot));
        }
        try (RsyncRuntimeFiles runtimeFiles = RsyncRuntimeFiles.create(
            connection,
            pinnedHostKey,
            job.isHostKeyVerificationDisabled(),
            auth,
            redactor)) {

            RsyncCommandBuilder.BuiltRsyncCommand command = commandBuilder.build(
                new RsyncCommandBuilder.RsyncCommandInput(
                    rsyncBinary,
                    sshBinary,
                    connection,
                    action,
                    runtimeFiles.knownHostsFile().orElse(null),
                    auth.privateKeyPath(),
                    auth.authMethod(),
                    job.isHostKeyVerificationDisabled(),
                    effectiveTargetRoot));
            RsyncProcessResult result = processExecutor.execute(command.arguments(), runtimeFiles.environment());
            return result.isSuccess()
                ? JobExecutionOutcome.success("Rsync completed.", result.stdout(), result.stderr(), command.displayCommand())
                : JobExecutionOutcome.failed("Rsync failed.", result.exitCode(), result.stdout(), result.stderr(), command.displayCommand());
        }
    }

    private String effectiveTargetRoot(JobAction action, ServerConnection connection, int targetCount) {
        String targetRoot = requireNonBlank(action.getRsyncTargetRoot(), "Rsync target root is required.");
        if (action.getRsyncDirection() != RsyncDirection.DOWNLOAD || targetCount <= 1) {
            return targetRoot;
        }
        return Path.of(targetRoot, safeTargetDirectoryName(connection)).toString();
    }

    private String safeTargetDirectoryName(ServerConnection connection) {
        String label = connection.getDisplayName();
        String safe = label != null
            ? label.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("_+", "_")
            : "target";
        if (safe.isBlank()) {
            safe = "target";
        }
        String id = connection.getId();
        if (id != null && id.length() >= 8) {
            return safe + "-" + id.substring(0, 8);
        }
        return safe;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static final class RsyncRuntimeFiles implements AutoCloseable {
        private final Path knownHostsFile;
        private final Path secretFile;
        private final Path askpassFile;
        private final Map<String, String> environment;

        private RsyncRuntimeFiles(
            Path knownHostsFile,
            Path secretFile,
            Path askpassFile,
            Map<String, String> environment) {

            this.knownHostsFile = knownHostsFile;
            this.secretFile = secretFile;
            this.askpassFile = askpassFile;
            this.environment = environment;
        }

        static RsyncRuntimeFiles create(
            ServerConnection connection,
            PinnedHostKey pinnedHostKey,
            boolean hostKeyVerificationDisabled,
            JobSchedulerRemoteSession.ExternalSshAuthMaterial auth,
            JobSchedulerSecretRedactor redactor) throws Exception {

            Path knownHosts = null;
            Path secretFile = null;
            Path askpassFile = null;
            Map<String, String> env = new HashMap<>();
            try {
                knownHosts = hostKeyVerificationDisabled
                    ? null
                    : createKnownHostsFile(connection, pinnedHostKey);
                String secret = auth.authMethod() == AuthMethod.PASSWORD
                    ? auth.password().orElse(null)
                    : auth.privateKeyPassphrase().orElse(null);
                if (secret != null && !secret.isBlank()) {
                    secretFile = Files.createTempFile("kortty-rsync-secret-", ".txt");
                    Files.writeString(secretFile, secret + System.lineSeparator(), StandardCharsets.UTF_8);
                    setOwnerPermissions(secretFile, OWNER_READ_WRITE);
                    askpassFile = Files.createTempFile("kortty-rsync-askpass-", ".sh");
                    Files.writeString(askpassFile, askpassScript(), StandardCharsets.UTF_8);
                    setOwnerPermissions(askpassFile, OWNER_READ_WRITE_EXECUTE);
                    env.put("SSH_ASKPASS", askpassFile.toString());
                    env.put("SSH_ASKPASS_REQUIRE", "force");
                    env.put("KORTTY_RSYNC_ASKPASS_SECRET_FILE", secretFile.toString());
                    env.putIfAbsent("DISPLAY", "localhost:0");
                    redactor.addSecret(secret);
                    redactor.addSecret(secretFile.toString());
                    redactor.addSecret(askpassFile.toString());
                }
                if (knownHosts != null) {
                    redactor.addSecret(knownHosts.toString());
                }
                return new RsyncRuntimeFiles(knownHosts, secretFile, askpassFile, env);
            } catch (Exception e) {
                deleteQuietly(secretFile);
                deleteQuietly(askpassFile);
                deleteQuietly(knownHosts);
                throw e;
            }
        }

        Optional<Path> knownHostsFile() {
            return Optional.ofNullable(knownHostsFile);
        }

        Map<String, String> environment() {
            return environment;
        }

        @Override
        public void close() {
            deleteQuietly(secretFile);
            deleteQuietly(askpassFile);
            deleteQuietly(knownHostsFile);
        }

        private static Path createKnownHostsFile(
            ServerConnection connection,
            PinnedHostKey pinnedHostKey) throws Exception {

            if (pinnedHostKey == null || pinnedHostKey.getPublicKeyLine() == null || pinnedHostKey.getPublicKeyLine().isBlank()) {
                throw new JobBlockedException("Rsync requires a pinned OpenSSH host key. Confirm the host key again for this target.");
            }
            Path knownHosts = Files.createTempFile("kortty-rsync-known-hosts-", ".txt");
            try {
                String line = knownHostsHost(connection) + " " + pinnedHostKey.getPublicKeyLine() + System.lineSeparator();
                Files.writeString(knownHosts, line, StandardCharsets.UTF_8);
                setOwnerPermissions(knownHosts, OWNER_READ_WRITE);
                return knownHosts;
            } catch (Exception e) {
                deleteQuietly(knownHosts);
                throw e;
            }
        }

        private static String knownHostsHost(ServerConnection connection) {
            String host = connection.getHost();
            int port = Math.max(1, connection.getPort());
            if (port != 22 || (host != null && host.contains(":"))) {
                return "[" + host + "]:" + port;
            }
            return host;
        }

        private static String askpassScript() {
            return """
                #!/bin/sh
                if [ -r "$KORTTY_RSYNC_ASKPASS_SECRET_FILE" ]; then
                  cat "$KORTTY_RSYNC_ASKPASS_SECRET_FILE"
                fi
                """;
        }

        private static void setOwnerPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
            try {
                Files.setPosixFilePermissions(path, permissions);
            } catch (UnsupportedOperationException e) {
                path.toFile().setReadable(false, false);
                path.toFile().setWritable(false, false);
                path.toFile().setExecutable(false, false);
                path.toFile().setReadable(true, true);
                path.toFile().setWritable(true, true);
                path.toFile().setExecutable(permissions.contains(PosixFilePermission.OWNER_EXECUTE), true);
            }
        }

        private static void deleteQuietly(Path path) {
            if (path == null) {
                return;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.debug("Could not delete temporary Rsync file {}", path, e);
            }
        }
    }
}
