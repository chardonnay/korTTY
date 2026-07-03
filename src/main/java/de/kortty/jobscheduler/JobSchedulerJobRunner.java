package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class JobSchedulerJobRunner {

    private final KorTTYApplication app;
    private final JobSchedulerRepository repository;
    private final JobSchedulerConnectionResolver connectionResolver;
    private final JobSchedulerSudoService sudoService;
    private final EncryptionService encryptionService = new EncryptionService();
    private final JobSchedulerArchiveCommandBuilder archiveCommandBuilder = new JobSchedulerArchiveCommandBuilder();
    private final JobSchedulerAiSupport aiSupport;
    private final JobSchedulerAiSwarmSupport aiSwarmSupport;
    private final JobSchedulerSnippetSupport snippetSupport;
    private final JobSchedulerRsyncSupport rsyncSupport;

    public JobSchedulerJobRunner(KorTTYApplication app, JobSchedulerRepository repository) {
        this(app, repository, new JobSchedulerRsyncSupport(app));
    }

    JobSchedulerJobRunner(
        KorTTYApplication app,
        JobSchedulerRepository repository,
        JobSchedulerRsyncSupport rsyncSupport) {

        this.app = app;
        this.repository = repository;
        this.connectionResolver = new JobSchedulerConnectionResolver(app);
        this.sudoService = new JobSchedulerSudoService(repository);
        this.aiSupport = new JobSchedulerAiSupport(app);
        this.aiSwarmSupport = new JobSchedulerAiSwarmSupport(app, this.aiSupport);
        this.snippetSupport = new JobSchedulerSnippetSupport(
            app != null ? app.getSnippetManager() : null,
            app != null ? app.getSnippetVariableManager() : null);
        this.rsyncSupport = rsyncSupport;
    }

    public JobExecutionOutcome run(ScheduledJob job, String runId) {
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        try {
            List<ServerConnection> targets = connectionResolver.resolveTargets(job);
            JobExecutionOutcome outcome;
            if (job.getAction() != null && job.getAction().getType() == JobActionType.AI_SWARM) {
                // The swarm gets ALL targets at once (parallel agents + one aggregated report),
                // never the sequential per-connection loop.
                outcome = runAiSwarm(job, runId, targets, redactor);
            } else {
                outcome = targets.size() == 1
                    ? runForConnection(job, runId, targets.get(0), redactor, targets.size())
                    : runForTargets(job, runId, targets, redactor);
            }
            return sanitizeOutcome(outcome, job.getJournalDetailMode(), redactor);
        } catch (JobBlockedException e) {
            return JobExecutionOutcome.blocked(e.getMessage(), e.getMessage());
        } catch (Exception e) {
            return sanitizeOutcome(
                JobExecutionOutcome.failed("Job failed: " + safeMessage(e), -1, null, safeMessage(e), exceptionDetail(e)),
                job.getJournalDetailMode(),
                redactor);
        }
    }

    public PinnedHostKey probeHostKey(String connectionId) throws Exception {
        ServerConnection connection = connectionResolver.resolve(connectionId);
        return JobSchedulerRemoteSession.probeHostKey(connection);
    }

    private JobExecutionOutcome runForTargets(
        ScheduledJob job,
        String runId,
        List<ServerConnection> targets,
        JobSchedulerSecretRedactor redactor) {

        int successCount = 0;
        int failedCount = 0;
        int blockedCount = 0;
        int exitCode = 0;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        for (ServerConnection target : targets) {
            JobExecutionOutcome outcome;
            try {
                outcome = runForConnection(job, runId, target, redactor, targets.size());
            } catch (JobBlockedException e) {
                outcome = JobExecutionOutcome.blocked(e.getMessage(), e.getMessage());
            } catch (Exception e) {
                outcome = JobExecutionOutcome.failed(
                    "Target failed: " + safeMessage(e),
                    -1,
                    null,
                    safeMessage(e),
                    exceptionDetail(e));
            }

            if (outcome.status() == JobRunStatus.SUCCESS) {
                successCount++;
            } else if (outcome.status() == JobRunStatus.BLOCKED) {
                blockedCount++;
                if (exitCode == 0) {
                    exitCode = outcome.exitCode();
                }
            } else {
                failedCount++;
                if (exitCode == 0) {
                    exitCode = outcome.exitCode();
                }
            }
            appendTargetOutput(stdout, target, outcome.stdout());
            appendTargetOutput(stderr, target, outcome.stderr());
            appendTargetOutput(detail, target, outcome.detail());
        }

        int total = targets.size();
        String summary = "Job completed on " + successCount + " of " + total + " target(s).";
        if (failedCount > 0 || blockedCount > 0) {
            summary += " Failed: " + failedCount + ", blocked: " + blockedCount + ".";
        }
        JobRunStatus status = failedCount > 0
            ? JobRunStatus.FAILED
            : blockedCount > 0 ? JobRunStatus.BLOCKED : JobRunStatus.SUCCESS;
        return new JobExecutionOutcome(
            status,
            summary,
            exitCode,
            emptyToNull(stdout),
            emptyToNull(stderr),
            emptyToNull(detail));
    }

    /**
     * AI_SWARM bypasses {@code runForConnection}, so its master-password and host-key gates are
     * enforced here explicitly (fail fast: whole job BLOCKED) before any session is opened.
     */
    private JobExecutionOutcome runAiSwarm(
        ScheduledJob job,
        String runId,
        List<ServerConnection> targets,
        JobSchedulerSecretRedactor redactor) throws Exception {

        if (targets == null || targets.isEmpty()) {
            throw new JobBlockedException("No target connections are configured for this job.");
        }
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (masterPassword == null) {
            throw new JobBlockedException("Master password is locked; required job secrets are unavailable.");
        }
        List<PinnedHostKey> hostKeys = new java.util.ArrayList<>(targets.size());
        for (ServerConnection target : targets) {
            hostKeys.add(resolvePinnedHostKeyForJob(job, target));
        }
        JobExecutionOutcome outcome = aiSwarmSupport.runAiSwarm(
            job, runId, targets, hostKeys, masterPassword, redactor);
        return addHostKeyVerificationNotice(job, outcome);
    }

    private JobExecutionOutcome runForConnection(
        ScheduledJob job,
        String runId,
        ServerConnection connection,
        JobSchedulerSecretRedactor redactor,
        int targetCount) throws Exception {

        PinnedHostKey hostKey = resolvePinnedHostKeyForJob(job, connection);
        char[] masterPassword = app.getMasterPasswordManager() != null
            ? app.getMasterPasswordManager().getMasterPassword()
            : null;
        if (masterPassword == null && needsSecrets(job)) {
            throw new JobBlockedException("Master password is locked; required job secrets are unavailable.");
        }
        Optional<String> sudoPassword = job.getAction().isUseSudo() && job.getAction().getType() != JobActionType.RSYNC_SYNC
            ? sudoService.resolveSudoPassword(connection, masterPassword)
            : Optional.empty();
        sudoPassword.ifPresent(redactor::addSecret);
        String archivePassword = decryptArchivePassword(job.getAction(), masterPassword);
        redactor.addSecret(archivePassword);

        try (JobSchedulerRemoteSession remote = new JobSchedulerRemoteSession(
            app,
            connection,
            hostKey,
            masterPassword,
            job.isHostKeyVerificationDisabled())) {
            remote.connect();
            remote.getPassword().ifPresent(redactor::addSecret);
            JobExecutionOutcome outcome = executeAction(
                job,
                runId,
                connection,
                hostKey,
                remote,
                remote.externalSshAuthMaterial(),
                sudoPassword.orElse(null),
                archivePassword,
                targetCount,
                redactor);
            return addHostKeyVerificationNotice(job, outcome);
        }
    }

    PinnedHostKey resolvePinnedHostKeyForJob(ScheduledJob job, ServerConnection connection) throws JobBlockedException {
        if (job != null && job.isHostKeyVerificationDisabled()) {
            return null;
        }
        return repository.findPinnedHostKey(connection.getId())
            .orElseThrow(() -> new JobBlockedException(
                "Host key pinning is required before this job can run: " + connection.getDisplayName()));
    }

    private JobExecutionOutcome executeAction(
        ScheduledJob job,
        String runId,
        ServerConnection connection,
        PinnedHostKey hostKey,
        JobSchedulerRemoteSession remote,
        JobSchedulerRemoteSession.ExternalSshAuthMaterial externalAuth,
        String sudoPassword,
        String archivePassword,
        int targetCount,
        JobSchedulerSecretRedactor redactor) throws Exception {

        JobAction action = job.getAction();
        return switch (action.getType()) {
            case COMMAND -> executeCommand(job, remote, sudoPassword);
            case SNIPPET_SCRIPT -> executeSnippet(job, remote, sudoPassword);
            case AI_AGENT -> aiSupport.runAiAgent(
                job,
                new JobSchedulerAiSupport.ServerConnectionContext(connection.getDisplayName()),
                remote,
                sudoPassword,
                redactor);
            case AI_SWARM -> throw new IllegalStateException(
                "AI_SWARM is dispatched before per-connection execution");
            case SFTP_UPLOAD -> executeUpload(action, runId, remote, sudoPassword);
            case SFTP_DOWNLOAD -> executeDownload(action, runId, remote, sudoPassword);
            case SFTP_SYNC -> executeSync(action, runId, remote, sudoPassword);
            case SFTP_DELETE -> executeDelete(action, remote, sudoPassword);
            case SFTP_RENAME -> executeRename(action, remote, sudoPassword);
            case SFTP_MKDIR -> executeMkdir(action, remote, sudoPassword);
            case SFTP_CHMOD -> executeChmod(action, remote, sudoPassword);
            case SFTP_CHOWN -> executeChown(action, remote, sudoPassword);
            case SFTP_COPY_REMOTE -> executeRemoteCopy(action, remote, sudoPassword);
            case SFTP_ARCHIVE -> executeArchive(action, remote, sudoPassword, archivePassword);
            case RSYNC_SYNC -> rsyncSupport.run(job, connection, hostKey, externalAuth, targetCount, redactor);
        };
    }

    private JobExecutionOutcome executeCommand(ScheduledJob job, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String command = requireNonBlank(job.getAction().getCommand(), "Command is required.");
        return executeShellCommand(job, remote, sudoPassword, command, "Command completed.", "Command failed.", null);
    }

    private JobExecutionOutcome executeSnippet(ScheduledJob job, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        JobSchedulerSnippetSupport.BuiltSnippetScript snippet = snippetSupport.build(job.getAction());
        return executeShellCommand(
            job,
            remote,
            sudoPassword,
            snippet.command(),
            "Snippet script completed.",
            "Snippet script failed.",
            snippet.detail());
    }

    private JobExecutionOutcome executeShellCommand(
        ScheduledJob job,
        JobSchedulerRemoteSession remote,
        String sudoPassword,
        String command,
        String successSummary,
        String failureSummary,
        String detailOverride) throws Exception {

        if (job.getWorkingDirectory() != null && !job.getWorkingDirectory().isBlank()) {
            command = "cd " + ShellEscaper.quote(job.getWorkingDirectory()) + " && " + command;
        }
        String shellCommand = job.getAction().isUseSudo()
            ? JobSchedulerArchiveCommandBuilder.sudoWrap(command, sudoPassword)
            : "sh -lc " + ShellEscaper.quote(command);
        JobSchedulerRemoteSession.CommandResult result = remote.execute(shellCommand, sudoPassword != null ? sudoPassword + "\n" : null);
        String detail = detailOverride != null && !detailOverride.isBlank() ? detailOverride : shellCommand;
        return result.isSuccess()
            ? JobExecutionOutcome.success(successSummary, result.stdout(), result.stderr(), detail)
            : JobExecutionOutcome.failed(failureSummary, result.exitCode(), result.stdout(), result.stderr(), detail);
    }

    private JobExecutionOutcome executeUpload(JobAction action, String runId, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        Path localPath = Path.of(requireNonBlank(action.getLocalPath(), "Local path is required."));
        String remotePath = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        if (action.isUseSudo() && action.isSudoStagingEnabled()) {
            String tempPath = remote.tempRemotePath(localPath.getFileName() != null ? localPath.getFileName().toString() : runId);
            remote.upload(localPath, tempPath);
            JobSchedulerRemoteSession.CommandResult move = remote.execute(
                JobSchedulerArchiveCommandBuilder.sudoWrap("mv " + ShellEscaper.quote(tempPath) + " " + ShellEscaper.quote(remotePath), sudoPassword),
                sudoPassword != null ? sudoPassword + "\n" : null);
            return move.isSuccess()
                ? JobExecutionOutcome.success("Upload completed via sudo staging.", move.stdout(), move.stderr(), "temp=" + tempPath)
                : JobExecutionOutcome.failed("Upload staging move failed.", move.exitCode(), move.stdout(), move.stderr(), "temp=" + tempPath);
        }
        remote.upload(localPath, remotePath);
        return JobExecutionOutcome.success("Upload completed.", null, null, localPath + " -> " + remotePath);
    }

    private JobExecutionOutcome executeDownload(JobAction action, String runId, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String remotePath = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        Path localPath = Path.of(requireNonBlank(action.getLocalPath(), "Local path is required."));
        if (action.isUseSudo() && action.isSudoStagingEnabled()) {
            Path remoteFileName = Path.of(remotePath).getFileName();
            String tempPath = remote.tempRemotePath(remoteFileName != null ? remoteFileName.toString() : runId);
            String copyCommand = "cp -a " + ShellEscaper.quote(remotePath) + " " + ShellEscaper.quote(tempPath)
                + " && chmod -R u+rwX,go-rwx " + ShellEscaper.quote(tempPath);
            JobSchedulerRemoteSession.CommandResult copy = remote.execute(
                JobSchedulerArchiveCommandBuilder.sudoWrap(copyCommand, sudoPassword),
                sudoPassword != null ? sudoPassword + "\n" : null);
            if (!copy.isSuccess()) {
                return JobExecutionOutcome.failed("Download staging copy failed.", copy.exitCode(), copy.stdout(), copy.stderr(), "temp=" + tempPath);
            }
            try {
                remote.download(tempPath, localPath);
            } finally {
                remote.execute("rm -rf " + ShellEscaper.quote(tempPath));
            }
            return JobExecutionOutcome.success("Download completed via sudo staging.", copy.stdout(), copy.stderr(), "temp=" + tempPath);
        }
        remote.download(remotePath, localPath);
        return JobExecutionOutcome.success("Download completed.", null, null, remotePath + " -> " + localPath);
    }

    private JobExecutionOutcome executeSync(JobAction action, String runId, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        if (action.getSyncDirection() == SftpSyncDirection.DOWNLOAD) {
            return executeDownload(action, runId, remote, sudoPassword);
        }
        return executeUpload(action, runId, remote, sudoPassword);
    }

    private JobExecutionOutcome executeDelete(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String path = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        if (action.isUseSudo()) {
            return executeRemoteShell("Delete completed.", "rm -rf " + ShellEscaper.quote(path), remote, sudoPassword, true);
        }
        remote.deleteRemote(path);
        return JobExecutionOutcome.success("Delete completed.", null, null, path);
    }

    private JobExecutionOutcome executeRename(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String source = requireNonBlank(action.getRemoteSourcePath(), "Remote source path is required.");
        String destination = action.getRemoteDestinationPath();
        if (destination == null || destination.isBlank()) {
            destination = action.getNewName();
        }
        destination = requireNonBlank(destination, "Remote destination path is required.");
        if (action.isUseSudo()) {
            return executeRemoteShell("Rename completed.", "mv " + ShellEscaper.quote(source) + " " + ShellEscaper.quote(destination), remote, sudoPassword, true);
        }
        remote.renameRemote(source, destination);
        return JobExecutionOutcome.success("Rename completed.", null, null, source + " -> " + destination);
    }

    private JobExecutionOutcome executeMkdir(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String path = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        if (action.isUseSudo()) {
            return executeRemoteShell("Directory created.", "mkdir -p " + ShellEscaper.quote(path), remote, sudoPassword, true);
        }
        remote.mkdirs(path);
        return JobExecutionOutcome.success("Directory created.", null, null, path);
    }

    private JobExecutionOutcome executeChmod(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String path = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        String permissions = requireNonBlank(action.getPermissions(), "Permissions are required.");
        if (action.isUseSudo() || !isOctalPermissions(permissions)) {
            return executeRemoteShell("Permissions changed.", "chmod " + ShellEscaper.quote(permissions) + " " + ShellEscaper.quote(path), remote, sudoPassword, action.isUseSudo());
        }
        remote.chmodRemote(path, permissions);
        return JobExecutionOutcome.success("Permissions changed.", null, null, permissions + " " + path);
    }

    private JobExecutionOutcome executeChown(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String path = requireNonBlank(action.getRemotePath(), "Remote path is required.");
        String owner = requireNonBlank(action.getOwner(), "Owner is required.");
        String ownerSpec = action.getGroup() != null && !action.getGroup().isBlank()
            ? owner + ":" + action.getGroup()
            : owner;
        return executeRemoteShell("Owner changed.", "chown " + ShellEscaper.quote(ownerSpec) + " " + ShellEscaper.quote(path), remote, sudoPassword, action.isUseSudo());
    }

    private JobExecutionOutcome executeRemoteCopy(JobAction action, JobSchedulerRemoteSession remote, String sudoPassword) throws Exception {
        String source = requireNonBlank(action.getRemoteSourcePath(), "Remote source path is required.");
        String destination = requireNonBlank(action.getRemoteDestinationPath(), "Remote destination path is required.");
        return executeRemoteShell("Remote copy completed.", "cp -a " + ShellEscaper.quote(source) + " " + ShellEscaper.quote(destination), remote, sudoPassword, action.isUseSudo());
    }

    private JobExecutionOutcome executeArchive(
        JobAction action,
        JobSchedulerRemoteSession remote,
        String sudoPassword,
        String archivePassword) throws Exception {

        if (action.getArchiveFormat() == JobArchiveFormat.ZIP_PASSWORD
            && (archivePassword == null || archivePassword.isBlank())) {
            throw new JobBlockedException("Archive password is required for password-protected ZIP jobs.");
        }
        String command = archiveCommandBuilder.build(action, sudoPassword);
        String stdin = archiveStdin(action, sudoPassword, archivePassword);
        JobSchedulerRemoteSession.CommandResult result = remote.execute(command, stdin);
        boolean zipWarning = (action.getArchiveFormat() == JobArchiveFormat.ZIP
            || action.getArchiveFormat() == JobArchiveFormat.ZIP_PASSWORD)
            && result.exitCode() == 18;
        if (result.exitCode() != 0 && !zipWarning) {
            return JobExecutionOutcome.failed("Archive creation failed.", result.exitCode(), result.stdout(), result.stderr(), command);
        }
        String detail = command;
        if (action.isArchiveDownloadAfterCreate()) {
            Path localPath = Path.of(requireNonBlank(action.getArchiveDownloadLocalPath(), "Archive download local path is required."));
            remote.download(action.getArchivePath(), localPath);
            detail += "\ndownloaded to " + localPath;
        }
        return JobExecutionOutcome.success(zipWarning ? "Archive created with warnings." : "Archive created.", result.stdout(), result.stderr(), detail);
    }

    private String archiveStdin(JobAction action, String sudoPassword, String archivePassword) {
        StringBuilder stdin = new StringBuilder();
        if (sudoPassword != null) {
            stdin.append(sudoPassword).append('\n');
        }
        if (action.getArchiveFormat() == JobArchiveFormat.ZIP_PASSWORD) {
            stdin.append(archivePassword).append('\n').append(archivePassword).append('\n');
        }
        return stdin.isEmpty() ? null : stdin.toString();
    }

    private JobExecutionOutcome executeRemoteShell(
        String successSummary,
        String command,
        JobSchedulerRemoteSession remote,
        String sudoPassword,
        boolean useSudo) throws Exception {

        String shellCommand = useSudo
            ? JobSchedulerArchiveCommandBuilder.sudoWrap(command, sudoPassword)
            : "sh -lc " + ShellEscaper.quote(command);
        JobSchedulerRemoteSession.CommandResult result = remote.execute(shellCommand, sudoPassword != null ? sudoPassword + "\n" : null);
        return result.isSuccess()
            ? JobExecutionOutcome.success(successSummary, result.stdout(), result.stderr(), shellCommand)
            : JobExecutionOutcome.failed(successSummary + " Failed.", result.exitCode(), result.stdout(), result.stderr(), shellCommand);
    }

    private JobExecutionOutcome addHostKeyVerificationNotice(ScheduledJob job, JobExecutionOutcome outcome) {
        if (!job.isHostKeyVerificationDisabled()) {
            return outcome;
        }
        String detail = appendDetail(
            "Host-key verification is disabled for this job.",
            outcome.detail());
        return new JobExecutionOutcome(
            outcome.status(),
            outcome.summary(),
            outcome.exitCode(),
            outcome.stdout(),
            outcome.stderr(),
            detail);
    }

    private String appendDetail(String first, String second) {
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n" + second;
    }

    private boolean needsSecrets(ScheduledJob job) {
        JobAction action = job.getAction();
        return (action.isUseSudo() && action.getType() != JobActionType.RSYNC_SYNC)
            || action.getType() == JobActionType.AI_AGENT
            || (action.getType() == JobActionType.SFTP_ARCHIVE
                && action.getArchiveFormat() == JobArchiveFormat.ZIP_PASSWORD);
    }

    private String decryptArchivePassword(JobAction action, char[] masterPassword) throws Exception {
        if (action.getType() != JobActionType.SFTP_ARCHIVE
            || action.getArchiveFormat() != JobArchiveFormat.ZIP_PASSWORD) {
            return null;
        }
        String encrypted = action.getEncryptedArchivePassword();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        if (masterPassword == null) {
            throw new JobBlockedException("Master password is locked; archive password is unavailable.");
        }
        return encryptionService.decryptPassword(encrypted, masterPassword);
    }

    private boolean isOctalPermissions(String permissions) {
        return permissions != null && permissions.matches("[0-7]{3,4}");
    }

    private JobExecutionOutcome sanitizeOutcome(
        JobExecutionOutcome outcome,
        JournalDetailMode mode,
        JobSchedulerSecretRedactor redactor) {

        return new JobExecutionOutcome(
            outcome.status(),
            redactor.prepare(outcome.summary(), mode),
            outcome.exitCode(),
            redactor.prepare(outcome.stdout(), mode),
            redactor.prepare(outcome.stderr(), mode),
            redactor.prepare(outcome.detail(), mode));
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    private String exceptionDetail(Exception e) {
        StringBuilder builder = new StringBuilder(safeMessage(e));
        Throwable cause = e.getCause();
        if (cause != null) {
            builder.append("\nCause: ").append(cause.getClass().getSimpleName()).append(": ");
            if (cause.getMessage() != null) {
                builder.append(cause.getMessage());
            }
        }
        return builder.toString();
    }

    private void appendTargetOutput(StringBuilder builder, ServerConnection target, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("[").append(target.getDisplayName()).append("]\n").append(text);
    }

    private String emptyToNull(StringBuilder builder) {
        return builder.isEmpty() ? null : builder.toString();
    }
}
