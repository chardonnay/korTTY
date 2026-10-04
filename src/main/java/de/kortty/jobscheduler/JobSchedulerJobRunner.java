package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.core.AutomationJournalRecorder;
import de.kortty.core.AutomationJournalRun;
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
    private final java.util.function.Supplier<de.kortty.policy.EffectivePolicy> policy;

    public JobSchedulerJobRunner(KorTTYApplication app, JobSchedulerRepository repository) {
        this(app, repository, new JobSchedulerRsyncSupport(app));
    }

    JobSchedulerJobRunner(
        KorTTYApplication app,
        JobSchedulerRepository repository,
        JobSchedulerRsyncSupport rsyncSupport) {
        this(app, repository, rsyncSupport, de.kortty.policy.PolicyManager::effective);
    }

    JobSchedulerJobRunner(
        KorTTYApplication app,
        JobSchedulerRepository repository,
        JobSchedulerRsyncSupport rsyncSupport,
        java.util.function.Supplier<de.kortty.policy.EffectivePolicy> policy) {

        this.policy = policy;
        this.app = app;
        this.repository = repository;
        this.connectionResolver = new JobSchedulerConnectionResolver(app);
        this.sudoService = new JobSchedulerSudoService(repository);
        this.aiSupport = new JobSchedulerAiSupport(app, null, policy);
        this.aiSwarmSupport = new JobSchedulerAiSwarmSupport(app, this.aiSupport);
        this.snippetSupport = new JobSchedulerSnippetSupport(
            app != null ? app.getSnippetManager() : null,
            app != null ? app.getSnippetVariableManager() : null);
        this.rsyncSupport = rsyncSupport;
    }

    public JobExecutionOutcome run(ScheduledJob job, String runId) {
        return run(job, runId, AutomationJournalRun.NONE);
    }

    /** Runs the job, recording each target into {@code journalRun} (may be {@link AutomationJournalRun#NONE}). */
    public JobExecutionOutcome run(ScheduledJob job, String runId, AutomationJournalRun journalRun) {
        AutomationJournalRun journals = journalRun != null ? journalRun : AutomationJournalRun.NONE;
        JobSchedulerSecretRedactor redactor = new JobSchedulerSecretRedactor();
        Optional<String> refusal = fileTransferRefusal(job.getAction());
        if (refusal.isPresent()) {
            // Refused before any connection is made: nothing is resolved, opened or copied.
            return JobExecutionOutcome.failed(refusal.get(), -1, null, refusal.get(), null);
        }
        Optional<String> aiRefusal = aiPolicyRefusal(job.getAction());
        if (aiRefusal.isPresent()) {
            // Blocked before any connection is made: no target is resolved, no session opened,
            // no AI service created.
            return JobExecutionOutcome.blocked(aiRefusal.get(), aiRefusal.get());
        }
        try {
            List<ServerConnection> targets = connectionResolver.resolveTargets(job);
            JobExecutionOutcome outcome;
            if (job.getAction() != null && job.getAction().getType() == JobActionType.AI_SWARM) {
                // The swarm gets ALL targets at once (parallel agents + one aggregated report),
                // never the sequential per-connection loop.
                outcome = runAiSwarm(job, runId, targets, redactor, journals);
            } else {
                outcome = targets.size() == 1
                    ? runForConnection(job, runId, targets.get(0), redactor, targets.size(), journals)
                    : runForTargets(job, runId, targets, redactor, journals);
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

    /**
     * The policy refusal for an action that copies files between this computer and a server (D6:
     * SFTP upload, download and sync, and rsync), or empty when the action may run. Remote-only
     * SFTP actions are never refused here.
     */
    Optional<String> fileTransferRefusal(JobAction action) {
        if (action == null || action.getType() == null) {
            return Optional.empty();
        }
        de.kortty.policy.FileTransferGate.Route route = switch (action.getType()) {
            case SFTP_UPLOAD -> de.kortty.policy.FileTransferGate.Route.JOB_SFTP_UPLOAD;
            case SFTP_DOWNLOAD -> de.kortty.policy.FileTransferGate.Route.JOB_SFTP_DOWNLOAD;
            case SFTP_SYNC -> de.kortty.policy.FileTransferGate.Route.JOB_SFTP_SYNC;
            // rsync copies between this computer and the server as well; leaving it open would
            // defeat a data-loss-prevention deny.
            case RSYNC_SYNC -> de.kortty.policy.FileTransferGate.Route.JOB_RSYNC_SYNC;
            default -> null;
        };
        if (route == null) {
            return Optional.empty();
        }
        de.kortty.policy.FileTransferGate.Verdict verdict =
            de.kortty.policy.FileTransferGate.check(policy.get(), route);
        return verdict.allowed() ? Optional.empty() : Optional.of(verdict.reason());
    }

    /**
     * The policy refusal for a scheduled AI job, or empty when it may run (D8). An AI agent job
     * needs the agent allowed (which includes AI as a whole); an AI swarm job needs the swarm and
     * the agent allowed, because every swarm member is a full agent run. Agent execution READ_ONLY
     * refuses both: the terminal agent refuses every run under it, and an unattended job cannot
     * get anything done without the agent.
     */
    Optional<String> aiPolicyRefusal(JobAction action) {
        return aiPolicyRefusal(action, policy.get());
    }

    static Optional<String> aiPolicyRefusal(JobAction action, de.kortty.policy.EffectivePolicy effective) {
        if (action == null
            || (action.getType() != JobActionType.AI_AGENT && action.getType() != JobActionType.AI_SWARM)) {
            return Optional.empty();
        }
        de.kortty.policy.EffectivePolicy current =
            effective != null ? effective : de.kortty.policy.EffectivePolicy.unrestricted();
        boolean swarm = action.getType() == JobActionType.AI_SWARM;
        if (!current.aiAgentAllowed()) {
            return Optional.of(de.kortty.ui.I18n.get("jobscheduler.dialog.policy.aiAgentDenied"));
        }
        if (swarm && !current.aiSwarmAllowed()) {
            return Optional.of(de.kortty.ui.I18n.get("jobscheduler.dialog.policy.aiSwarmDenied"));
        }
        if (current.agentExecution() == de.kortty.policy.AgentExecutionMode.READ_ONLY) {
            return Optional.of(de.kortty.ui.I18n.get("jobscheduler.dialog.policy.aiReadOnly"));
        }
        return Optional.empty();
    }

    public PinnedHostKey probeHostKey(String connectionId) throws Exception {
        ServerConnection connection = connectionResolver.resolve(connectionId);
        return JobSchedulerRemoteSession.probeHostKey(connection);
    }

    private JobExecutionOutcome runForTargets(
        ScheduledJob job,
        String runId,
        List<ServerConnection> targets,
        JobSchedulerSecretRedactor redactor,
        AutomationJournalRun journals) {

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
                outcome = runForConnection(job, runId, target, redactor, targets.size(), journals);
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
        JobSchedulerSecretRedactor redactor,
        AutomationJournalRun journals) throws Exception {

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
            job, runId, targets, hostKeys, masterPassword, redactor, journals);
        return addHostKeyVerificationNotice(job, outcome);
    }

    /**
     * Runs the job on one target and records it into that target's session journal: every
     * command with its output, the job's summary, and the target's own outcome.
     */
    private JobExecutionOutcome runForConnection(
        ScheduledJob job,
        String runId,
        ServerConnection connection,
        JobSchedulerSecretRedactor redactor,
        int targetCount,
        AutomationJournalRun journals) throws Exception {

        AutomationJournalRecorder recorder = journals.recorderFor(connection);
        try {
            JobExecutionOutcome outcome = runForConnection(job, runId, connection, redactor, targetCount, recorder);
            if (recorder.getCommandCount() == 0) {
                // SFTP and rsync actions send no shell command: describe the action instead.
                recorder.appendCommand(describeAction(job.getAction()));
                recorder.appendOutput(outcome.stdout(), outcome.stderr());
            }
            recorder.appendNote(localizedStatus(outcome.status()), outcome.summary());
            recorder.setTargetStatus(JobSchedulerService.toAutomationStatus(outcome.status()));
            return outcome;
        } catch (JobBlockedException e) {
            recorder.appendNote(localizedStatus(JobRunStatus.BLOCKED), e.getMessage());
            recorder.setTargetStatus(de.kortty.model.AutomationRunStatus.BLOCKED);
            throw e;
        } catch (Exception e) {
            recorder.appendNote(localizedStatus(JobRunStatus.FAILED), safeMessage(e));
            recorder.setTargetStatus(de.kortty.model.AutomationRunStatus.FAILED);
            throw e;
        }
    }

    private static String localizedStatus(JobRunStatus status) {
        return de.kortty.ui.I18n.get("jobscheduler.dialog.status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** One line for the journal that says what an action without shell commands did. */
    static String describeAction(JobAction action) {
        if (action == null || action.getType() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(action.getType().name().toLowerCase(java.util.Locale.ROOT));
        if (action.getType() == JobActionType.AI_AGENT && action.getAiPrompt() != null && !action.getAiPrompt().isBlank()) {
            return text.append(": ").append(action.getAiPrompt().strip().lines().findFirst().orElse("")).toString();
        }
        for (String part : new String[] {
            action.getLocalPath(), action.getRemotePath(), action.getRemoteSourcePath(),
            action.getRemoteDestinationPath(), action.getNewName(), action.getArchivePath(),
            action.getRsyncTargetRoot()}) {
            if (part != null && !part.isBlank()) {
                text.append(' ').append(part.strip());
            }
        }
        return text.toString();
    }

    private JobExecutionOutcome runForConnection(
        ScheduledJob job,
        String runId,
        ServerConnection connection,
        JobSchedulerSecretRedactor redactor,
        int targetCount,
        AutomationJournalRecorder recorder) throws Exception {

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
        sudoPassword.ifPresent(recorder::addSecret);
        String archivePassword = decryptArchivePassword(job.getAction(), masterPassword);
        redactor.addSecret(archivePassword);
        recorder.addSecret(archivePassword);

        try (JobSchedulerRemoteSession remote = new JobSchedulerRemoteSession(
            app,
            connection,
            hostKey,
            masterPassword,
            job.isHostKeyVerificationDisabled())) {
            remote.connect();
            remote.getPassword().ifPresent(redactor::addSecret);
            remote.getPassword().ifPresent(recorder::addSecret);
            if (recorder.isRecording()) {
                remote.setCommandObserver((label, result) -> {
                    recorder.appendCommand(label);
                    if (result == null) {
                        recorder.appendLogNote("failed or cancelled");
                        return;
                    }
                    recorder.appendOutput(result.stdout(), result.stderr());
                    if (result.exitCode() != 0) {
                        recorder.appendLogNote("exit " + result.exitCode());
                    }
                });
            }
            JobExecutionOutcome outcome = executeAction(
                job,
                runId,
                connection,
                hostKey,
                remote,
                sudoPassword.orElse(null),
                archivePassword,
                targetCount,
                redactor,
                recorder);
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
        String sudoPassword,
        String archivePassword,
        int targetCount,
        JobSchedulerSecretRedactor redactor,
        AutomationJournalRecorder recorder) throws Exception {

        JobAction action = job.getAction();
        return switch (action.getType()) {
            case COMMAND -> executeCommand(job, remote, sudoPassword, recorder);
            case SNIPPET_SCRIPT -> executeSnippet(job, remote, sudoPassword, recorder);
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
            // Only Rsync's external ssh needs the key as a file, so a temporary key is written only here.
            case RSYNC_SYNC -> rsyncSupport.run(
                job, connection, hostKey, remote.externalSshAuthMaterial(), targetCount, redactor);
        };
    }

    private JobExecutionOutcome executeCommand(ScheduledJob job, JobSchedulerRemoteSession remote, String sudoPassword,
                                               AutomationJournalRecorder recorder) throws Exception {
        String command = requireNonBlank(job.getAction().getCommand(), "Command is required.");
        return executeShellCommand(job, remote, sudoPassword, command, "Command completed.", "Command failed.", null, recorder);
    }

    private JobExecutionOutcome executeSnippet(ScheduledJob job, JobSchedulerRemoteSession remote, String sudoPassword,
                                               AutomationJournalRecorder recorder) throws Exception {
        JobSchedulerSnippetSupport.BuiltSnippetScript snippet = snippetSupport.build(job.getAction());
        return executeShellCommand(
            job,
            remote,
            sudoPassword,
            snippet.command(),
            "Snippet script completed.",
            "Snippet script failed.",
            snippet.detail(),
            recorder);
    }

    private JobExecutionOutcome executeShellCommand(
        ScheduledJob job,
        JobSchedulerRemoteSession remote,
        String sudoPassword,
        String command,
        String successSummary,
        String failureSummary,
        String detailOverride,
        AutomationJournalRecorder recorder) throws Exception {

        if (job.getWorkingDirectory() != null && !job.getWorkingDirectory().isBlank()) {
            command = "cd " + ShellEscaper.quote(job.getWorkingDirectory()) + " && " + command;
        }
        String shellCommand = job.getAction().isUseSudo()
            ? JobSchedulerArchiveCommandBuilder.sudoWrap(command, sudoPassword)
            : "sh -lc " + ShellEscaper.quote(command);
        String label = job.getAction().isUseSudo() ? "sudo " + command : command;
        if (job.getAction().isPtyEnabled() && recorder != null && recorder.isRecording()) {
            if (sudoPassword != null && !sudoPassword.isEmpty()) {
                // A password sent to a pseudo terminal can be echoed onto the screen — and into a
                // screenshot. Run such commands without the virtual terminal.
                recorder.appendLogNote("virtual terminal not used: the command sends a stored sudo password");
            } else {
                String detail = detailOverride != null && !detailOverride.isBlank() ? detailOverride : shellCommand;
                return JobSchedulerVirtualTerminalSupport.run(
                    job.getAction(), remote, shellCommand, label, successSummary, failureSummary, detail, recorder);
            }
        }
        remote.labelNextCommand(label);
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
