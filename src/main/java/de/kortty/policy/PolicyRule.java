package de.kortty.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One {@code [[rule]]} table from the policy file. Scope: a rule naming neither users nor groups
 * applies to every user; otherwise it applies to the union of the named users and the members of
 * the named groups (TOML-defined or OS/AD groups). All setting fields are nullable — null means
 * "not set at this tier", so lower-precedence tiers may still contribute the value.
 *
 * @param name                       optional rule name, used in log messages
 * @param users                      lowercased user names this rule targets (empty = not user-scoped)
 * @param groups                     lowercased group names this rule targets (empty = not group-scoped)
 * @param servers                    server allow/deny restriction, or null
 * @param features                   per-feature decisions from {@code [rule.features]}
 * @param agentExecution             agent execution mode, or null
 * @param requireMasterPassword      force the master-password startup gate
 * @param enforceHostKeyCheck        forbid disabling host key verification at any scope
 * @param clipboardMode              INTERNAL confines korTTY to its own in-memory clipboard
 * @param allowTelemetry             false forbids telemetry
 * @param allowTerminalRecording     false forbids terminal recording
 * @param allowPortForwarding        false forbids every SSH tunnel (local, remote and dynamic
 *                                   port forwarding) configured on a connection
 * @param allowCustomTeamworkSources false restricts teamwork to policy-provided sources
 * @param allowCustomScriptHeaders   false forbids creating own script headers
 * @param aiProfileAllowCreate       false forbids creating AI profiles
 * @param aiProfileAllowEdit         false forbids editing existing user AI profiles
 * @param aiProfileAllowInternet     false forbids every AI internet-access mode, so AI requests
 *                                   can never reach a web-search or MCP browsing backend
 * @param allowRuntimeDownloads      false forbids AI runtime downloads/updates
 * @param allowModelDownloads        false forbids LLM model downloads
 * @param allowUserModels            false restricts model selection to policy-provisioned models
 * @param updatesEnabled             false disables update checks and downloads entirely
 * @param updateFeedUrl              custom release feed replacing the GitHub endpoint, or null
 * @param loadIntoSnippetEditor      mode for the terminal "load into snippet editor" feature, or null
 * @param logging                    admin log configuration from {@code [rule.logging]}, or null
 * @param sessionJournal             session journal mandates from {@code [rule.session-journal]}, or null
 * @param snippetAnalysisMaxStoredContentBytes upper bound in bytes of UTF-8 for the script text
 *                                   stored with each Full-code analysis ({@code [rule.snippets]
 *                                   analysis-max-stored-content-bytes}); 0 forbids storing script
 *                                   text, null leaves it to the user
 * @param pasteWarningFloor          the least a terminal paste warning may ask ({@code [rule.terminal]
 *                                   paste-warning}); applied on top of Settings, the connection's own
 *                                   mode and a teamwork connection's mode, null leaves it to the user
 * @param allowOsc52ClipboardWrite   false forbids programs in a terminal to put text on the clipboard
 *                                   with OSC 52 ({@code [rule.security] allow-osc52-clipboard-write});
 *                                   true or null leaves the (default-off) choice to the user
 * @param sessionRestoreMode         the startup session restore mode the policy sets and locks
 *                                   ({@code [rule.terminal] session-restore}), or null
 * @param sessionRestoreOutput       whether the output of each terminal pane is restored with the
 *                                   session; the policy sets and locks the switch ({@code [rule.terminal]
 *                                   session-restore-output}), null leaves it to the user
 */
public record PolicyRule(
    String name,
    Set<String> users,
    Set<String> groups,
    ServerRestriction servers,
    Map<PolicyFeature, PolicyDecision> features,
    AgentExecutionMode agentExecution,
    Boolean requireMasterPassword,
    Boolean enforceHostKeyCheck,
    ClipboardMode clipboardMode,
    Boolean allowTelemetry,
    Boolean allowTerminalRecording,
    Boolean allowPortForwarding,
    Boolean allowCustomTeamworkSources,
    Boolean allowCustomScriptHeaders,
    Boolean aiProfileAllowCreate,
    Boolean aiProfileAllowEdit,
    Boolean aiProfileAllowInternet,
    Boolean allowRuntimeDownloads,
    Boolean allowModelDownloads,
    Boolean allowUserModels,
    Boolean updatesEnabled,
    String updateFeedUrl,
    LoadIntoEditorMode loadIntoSnippetEditor,
    LoggingRule logging,
    SessionJournalRule sessionJournal,
    Long snippetAnalysisMaxStoredContentBytes,
    de.kortty.paste.PasteWarningMode pasteWarningFloor,
    Boolean allowOsc52ClipboardWrite,
    de.kortty.model.SessionRestoreMode sessionRestoreMode,
    Boolean sessionRestoreOutput) {

    public PolicyRule {
        users = Set.copyOf(users);
        groups = Set.copyOf(groups);
        features = Map.copyOf(features);
    }

    /** True when the rule names neither users nor groups and therefore applies to everyone. */
    public boolean appliesToAll() {
        return users.isEmpty() && groups.isEmpty();
    }

    /**
     * The {@code [rule.logging]} table: where and how log files are written. All fields nullable =
     * "not set at this tier".
     *
     * @param directory          log directory (absolute or {@code ~/}-relative), or null
     * @param retentionDays      days rotated logs are kept (0 = unlimited), or null
     * @param compress           whether rotated logs are gzip-compressed after a day, or null
     * @param format             file log format, or null
     * @param rotationMaxFiles   maximum number of rotated daily files kept by logback (0 = unlimited), or null
     * @param rotationTotalSizeMb total size cap over all rotated files in MB (0 = uncapped), or null
     */
    public record LoggingRule(
        String directory,
        Integer retentionDays,
        Boolean compress,
        LogFormat format,
        Integer rotationMaxFiles,
        Integer rotationTotalSizeMb) {

        public boolean isEmpty() {
            return directory == null && retentionDays == null && compress == null
                && format == null && rotationMaxFiles == null && rotationTotalSizeMb == null;
        }
    }

    /**
     * The {@code [rule.session-journal]} table: admin mandates for the session journal. All fields
     * nullable = "not set at this tier".
     *
     * @param enforced      true forces a journal for every connection (users cannot stop it)
     * @param logFormat     capture-log format mandate: "xml", "json" or "yaml", or null
     * @param aiMaxLines    forced max terminal lines per AI evaluation (0 = context fill), or null
     * @param storagePath   forced journal storage directory, or null
     * @param allowRename   false forbids renaming journals in the manager
     * @param allowDelete   false forbids deleting journals in the manager
     * @param nameTemplate  initial journal title template with {connection}/{host}/{user}/{date}/{time}, or null
     * @param aiTitle       true forces the closing AI title regardless of the user setting
     * @param aiScreenshotAnalysis  true forces AI screenshot analysis on, false forbids it —
     *                      including manual per-screenshot runs; null leaves it to the user
     * @param aiAsk         false forbids on-demand AI over journal content (the viewer's Q&amp;A
     *                      panel and the manager's cross-journal AI search); null allows it
     * @param replacements  {@code [[rule.session-journal.replace]]} search-and-replace rules applied
     *                      to every captured line and every journal entry; never null, empty = none
     */
    public record SessionJournalRule(
        Boolean enforced,
        String logFormat,
        Integer aiMaxLines,
        String storagePath,
        Boolean allowRename,
        Boolean allowDelete,
        String nameTemplate,
        Boolean aiTitle,
        Boolean aiScreenshotAnalysis,
        Boolean aiAsk,
        Integer maxLogParts,
        List<de.kortty.model.SessionJournalReplacement> replacements,
        Boolean automationAllowed,
        Integer automationMaxRetentionDays,
        Integer automationMaxStorageMb,
        Integer automationMaxJournals) {

        public SessionJournalRule {
            replacements = replacements == null ? List.of() : List.copyOf(replacements);
        }

        /** A rule without automation-journal mandates. */
        public SessionJournalRule(
            Boolean enforced,
            String logFormat,
            Integer aiMaxLines,
            String storagePath,
            Boolean allowRename,
            Boolean allowDelete,
            String nameTemplate,
            Boolean aiTitle,
            Boolean aiScreenshotAnalysis,
            Boolean aiAsk,
            Integer maxLogParts,
            List<de.kortty.model.SessionJournalReplacement> replacements) {
            this(enforced, logFormat, aiMaxLines, storagePath, allowRename, allowDelete, nameTemplate,
                aiTitle, aiScreenshotAnalysis, aiAsk, maxLogParts, replacements, null, null, null, null);
        }

        public boolean isEmpty() {
            return enforced == null && logFormat == null && aiMaxLines == null && storagePath == null
                && allowRename == null && allowDelete == null && nameTemplate == null && aiTitle == null
                && aiScreenshotAnalysis == null && aiAsk == null && maxLogParts == null
                && replacements.isEmpty() && automationAllowed == null && automationMaxRetentionDays == null
                && automationMaxStorageMb == null && automationMaxJournals == null;
        }
    }

    /** Builder for tests and the loader — every field defaults to "not set". */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private Set<String> users = Set.of();
        private Set<String> groups = Set.of();
        private ServerRestriction servers;
        private Map<PolicyFeature, PolicyDecision> features = Map.of();
        private AgentExecutionMode agentExecution;
        private Boolean requireMasterPassword;
        private Boolean enforceHostKeyCheck;
        private ClipboardMode clipboardMode;
        private Boolean allowTelemetry;
        private Boolean allowTerminalRecording;
        private Boolean allowPortForwarding;
        private Boolean allowCustomTeamworkSources;
        private Boolean allowCustomScriptHeaders;
        private Boolean aiProfileAllowCreate;
        private Boolean aiProfileAllowEdit;
        private Boolean aiProfileAllowInternet;
        private Boolean allowRuntimeDownloads;
        private Boolean allowModelDownloads;
        private Boolean allowUserModels;
        private Boolean updatesEnabled;
        private String updateFeedUrl;
        private LoadIntoEditorMode loadIntoSnippetEditor;
        private LoggingRule logging;
        private SessionJournalRule sessionJournal;
        private Long snippetAnalysisMaxStoredContentBytes;
        private de.kortty.paste.PasteWarningMode pasteWarningFloor;
        private Boolean allowOsc52ClipboardWrite;
        private de.kortty.model.SessionRestoreMode sessionRestoreMode;
        private Boolean sessionRestoreOutput;

        public Builder name(String value) { this.name = value; return this; }
        public Builder users(Set<String> value) { this.users = value; return this; }
        public Builder groups(Set<String> value) { this.groups = value; return this; }
        public Builder servers(ServerRestriction value) { this.servers = value; return this; }
        public Builder features(Map<PolicyFeature, PolicyDecision> value) { this.features = value; return this; }
        public Builder agentExecution(AgentExecutionMode value) { this.agentExecution = value; return this; }
        public Builder requireMasterPassword(Boolean value) { this.requireMasterPassword = value; return this; }
        public Builder enforceHostKeyCheck(Boolean value) { this.enforceHostKeyCheck = value; return this; }
        public Builder clipboardMode(ClipboardMode value) { this.clipboardMode = value; return this; }
        public Builder allowTelemetry(Boolean value) { this.allowTelemetry = value; return this; }
        public Builder allowTerminalRecording(Boolean value) { this.allowTerminalRecording = value; return this; }
        public Builder allowPortForwarding(Boolean value) { this.allowPortForwarding = value; return this; }
        public Builder allowCustomTeamworkSources(Boolean value) { this.allowCustomTeamworkSources = value; return this; }
        public Builder allowCustomScriptHeaders(Boolean value) { this.allowCustomScriptHeaders = value; return this; }
        public Builder aiProfileAllowCreate(Boolean value) { this.aiProfileAllowCreate = value; return this; }
        public Builder aiProfileAllowEdit(Boolean value) { this.aiProfileAllowEdit = value; return this; }
        public Builder aiProfileAllowInternet(Boolean value) { this.aiProfileAllowInternet = value; return this; }
        public Builder allowRuntimeDownloads(Boolean value) { this.allowRuntimeDownloads = value; return this; }
        public Builder allowModelDownloads(Boolean value) { this.allowModelDownloads = value; return this; }
        public Builder allowUserModels(Boolean value) { this.allowUserModels = value; return this; }
        public Builder updatesEnabled(Boolean value) { this.updatesEnabled = value; return this; }
        public Builder updateFeedUrl(String value) { this.updateFeedUrl = value; return this; }
        public Builder loadIntoSnippetEditor(LoadIntoEditorMode value) { this.loadIntoSnippetEditor = value; return this; }
        public Builder logging(LoggingRule value) { this.logging = value; return this; }
        public Builder sessionJournal(SessionJournalRule value) { this.sessionJournal = value; return this; }
        public Builder snippetAnalysisMaxStoredContentBytes(Long value) {
            this.snippetAnalysisMaxStoredContentBytes = value;
            return this;
        }
        public Builder pasteWarningFloor(de.kortty.paste.PasteWarningMode value) {
            this.pasteWarningFloor = value;
            return this;
        }
        public Builder allowOsc52ClipboardWrite(Boolean value) { this.allowOsc52ClipboardWrite = value; return this; }
        public Builder sessionRestoreMode(de.kortty.model.SessionRestoreMode value) {
            this.sessionRestoreMode = value;
            return this;
        }
        public Builder sessionRestoreOutput(Boolean value) { this.sessionRestoreOutput = value; return this; }

        public PolicyRule build() {
            return new PolicyRule(name, users, groups, servers, features, agentExecution,
                requireMasterPassword, enforceHostKeyCheck, clipboardMode, allowTelemetry, allowTerminalRecording,
                allowPortForwarding, allowCustomTeamworkSources, allowCustomScriptHeaders, aiProfileAllowCreate,
                aiProfileAllowEdit, aiProfileAllowInternet, allowRuntimeDownloads, allowModelDownloads, allowUserModels,
                updatesEnabled, updateFeedUrl, loadIntoSnippetEditor, logging, sessionJournal,
                snippetAnalysisMaxStoredContentBytes, pasteWarningFloor, allowOsc52ClipboardWrite,
                sessionRestoreMode, sessionRestoreOutput);
        }
    }
}
