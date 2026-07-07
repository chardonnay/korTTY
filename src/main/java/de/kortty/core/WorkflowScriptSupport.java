package de.kortty.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, UI-free logic for turning a finished terminal-agent run into a single, self-contained,
 * independently runnable script (Bash/Python/Perl/Ruby/PowerShell/Windows-CMD/AppleScript) or
 * Ansible playbook.
 *
 * <p>All methods here are deterministic so they can be unit-tested without the JavaFX toolkit.
 * It builds the system/user prompts, the per-language error-handling idioms, strips markdown code
 * fences from the model output and injects a deterministic header as a safety net.
 */
public final class WorkflowScriptSupport {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_HEADER_REQUEST_CHARS = 200;
    private static final int HEADER_DETECTION_WINDOW = 1500;

    private WorkflowScriptSupport() {
    }

    /** The supported target languages. Ansible is declarative YAML, not a shebang script. */
    public enum ScriptLanguage {
        BASH("bash", "Bash", ".sh", "#!/usr/bin/env bash", false),
        PYTHON("python", "Python", ".py", "#!/usr/bin/env python3", false),
        PERL("perl", "Perl", ".pl", "#!/usr/bin/env perl", false),
        RUBY("ruby", "Ruby", ".rb", "#!/usr/bin/env ruby", false),
        POWERSHELL("powershell", "PowerShell", ".ps1", "#!/usr/bin/env pwsh", false),
        // Windows batch has no shebang; the leading line is "@echo off" (see leadLine()).
        WINDOWS_CMD("bat", "Windows-CMD", ".cmd", null, false),
        APPLESCRIPT("applescript", "AppleScript", ".applescript", "#!/usr/bin/osascript", false),
        ANSIBLE("yaml", "Ansible-Playbook", ".yml", null, true);

        private final String snippetLanguage;
        private final String displayName;
        private final String fileExtension;
        private final String shebang;
        private final boolean declarative;

        ScriptLanguage(String snippetLanguage, String displayName, String fileExtension,
                       String shebang, boolean declarative) {
            this.snippetLanguage = snippetLanguage;
            this.displayName = displayName;
            this.fileExtension = fileExtension;
            this.shebang = shebang;
            this.declarative = declarative;
        }

        public String snippetLanguage() {
            return snippetLanguage;
        }

        public String displayName() {
            return displayName;
        }

        public String fileExtension() {
            return fileExtension;
        }

        /** Shebang line for script languages; {@code null} for declarative Ansible playbooks. */
        public String shebang() {
            return shebang;
        }

        public boolean isDeclarative() {
            return declarative;
        }

        /** Line-comment prefix: {@code REM} for Windows-CMD, {@code --} for AppleScript, else {@code #}. */
        public String commentPrefix() {
            return switch (this) {
                case WINDOWS_CMD -> "REM";
                case APPLESCRIPT -> "--";
                default -> "#";
            };
        }

        /**
         * The mandatory first line of the artefact: the shebang for shebang scripts, {@code ---} for
         * Ansible, {@code @echo off} for Windows-CMD, or {@code null} when there is none.
         */
        public String leadLine() {
            if (declarative) {
                return "---";
            }
            if (shebang != null) {
                return shebang;
            }
            if (this == WINDOWS_CMD) {
                return "@echo off";
            }
            return null;
        }

        public static ScriptLanguage fromId(String id) {
            if (id == null) {
                return BASH;
            }
            return switch (id.trim().toLowerCase(Locale.ROOT)) {
                case "python", "py", "python3" -> PYTHON;
                case "perl", "pl" -> PERL;
                case "ruby", "rb" -> RUBY;
                case "powershell", "pwsh", "ps1", "ps" -> POWERSHELL;
                case "windows-cmd", "windowscmd", "cmd", "bat", "batch" -> WINDOWS_CMD;
                case "applescript", "osascript", "scpt" -> APPLESCRIPT;
                case "ansible", "ansible-playbook", "ansible_yaml", "yaml", "yml", "playbook" -> ANSIBLE;
                default -> BASH;
            };
        }
    }

    /**
     * Script-generation hardening features. The first group is always-on (pre-checked); the last
     * four are opt-in toggles. Each maps to one bullet in the system prompt (Ansible-specific
     * phrasing where the imperative idea differs).
     */
    public enum HardeningOption {
        STRICT_MODE,
        ERROR_TRAP_CLEANUP,
        MEANINGFUL_EXIT_CODES,
        LOGGING_VERBOSE,
        CONFIG_BLOCK,
        END_SUMMARY,
        STYLE_GUIDE_CLEAN,
        PRECONDITION_CHECKS,
        IDEMPOTENCY,
        SAFE_MODE,
        HELP_USAGE;

        /** Options enabled by default (all of them: always-on group + the four opt-in toggles). */
        public static EnumSet<HardeningOption> defaults() {
            return EnumSet.allOf(HardeningOption.class);
        }

        /** Always-on hardening that only documents/hardens without changing runtime behaviour. */
        public static EnumSet<HardeningOption> alwaysOn() {
            return EnumSet.of(STRICT_MODE, ERROR_TRAP_CLEANUP, MEANINGFUL_EXIT_CODES, LOGGING_VERBOSE,
                CONFIG_BLOCK, END_SUMMARY, STYLE_GUIDE_CLEAN);
        }

        /** Opt-in options that change control flow or add interactivity/checks. */
        public boolean isOptIn() {
            return this == PRECONDITION_CHECKS || this == IDEMPOTENCY || this == SAFE_MODE || this == HELP_USAGE;
        }

        /**
         * Parses a persisted comma-separated selection (enum names). {@code null} means "never saved" and
         * yields {@link #defaults()} (all options); an empty string yields an empty set (a saved "clear").
         * Unknown tokens are ignored.
         */
        public static EnumSet<HardeningOption> parseOptions(String csv) {
            if (csv == null) {
                return defaults();
            }
            EnumSet<HardeningOption> selected = EnumSet.noneOf(HardeningOption.class);
            for (String token : csv.split(",")) {
                String name = token.trim();
                if (name.isEmpty()) {
                    continue;
                }
                try {
                    selected.add(HardeningOption.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // Drop tokens from older/newer builds that no longer map to an option.
                }
            }
            return selected;
        }

        /** Serialises a selection to a comma-separated list of enum names (empty string for an empty set). */
        public static String serializeOptions(EnumSet<HardeningOption> options) {
            if (options == null || options.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (HardeningOption option : options) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(option.name());
            }
            return builder.toString();
        }
    }

    /** Authoritative header facts injected verbatim so the model cannot hallucinate them. */
    public record HeaderFacts(String scriptName, String creatorUser, String sshUser,
                              String connectionName, LocalDateTime generatedAt,
                              String sourcePrompt, String aiProfileName) {
    }

    /** Compacted, token-budgeted reproduction context (built by {@link WorkflowContextBuilder}). */
    public record WorkflowContext(String markdown, boolean truncated, int includedActions, int totalActions) {
    }

    /**
     * How the metadata header is handled after generation, so the model never emits a duplicate.
     * In every mode the app owns the header; the model only ever writes a functional description.
     */
    public enum HeaderMode {
        /** App injects the deterministic metadata header; the model adds a functional description. */
        AUTO,
        /** App prepends a user-defined header snippet; the model adds a functional description. */
        CUSTOM,
        /** No header block and no description — a bare artefact. */
        NONE
    }

    // ------------------------------------------------------------------ prompt assembly

    public static String buildSystemPrompt(ScriptLanguage lang, EnumSet<HardeningOption> opts) {
        return buildSystemPrompt(lang, opts, HeaderMode.AUTO);
    }

    /**
     * @param headerMode AUTO/CUSTOM: the metadata header is added by the app after generation, so the
     *                   model is told NOT to emit metadata but still writes a short functional
     *                   description. NONE: no header and no description block.
     */
    public static String buildSystemPrompt(ScriptLanguage lang, EnumSet<HardeningOption> opts, HeaderMode headerMode) {
        EnumSet<HardeningOption> options = opts != null ? opts : HardeningOption.defaults();
        String artefact = lang.isDeclarative() ? "Ansible playbook" : (lang.displayName() + " script");
        String unit = lang.isDeclarative() ? "playbook" : "script";
        StringBuilder sb = new StringBuilder();
        sb.append("You are a senior ").append(lang.displayName()).append(" engineer.\n");
        sb.append("Produce EXACTLY ONE self-contained, independently runnable ").append(artefact)
            .append(" that reproduces the work described in the user message.\n\n");
        sb.append("HARD REQUIREMENTS:\n");
        sb.append("- Output ONLY the ").append(unit)
            .append(". No prose, no explanations, no markdown code fences (never emit ``` lines).\n");
        if (lang.isDeclarative()) {
            sb.append("- Begin the file with '---'. It must be valid YAML, runnable with: ansible-playbook <file>.\n");
        } else if (lang.shebang() != null) {
            sb.append("- The first line MUST be the shebang: ").append(lang.shebang()).append("\n");
        } else if (lang == ScriptLanguage.WINDOWS_CMD) {
            sb.append("- The first line MUST be: @echo off  (then: setlocal EnableExtensions EnableDelayedExpansion).\n");
        }
        sb.append("- The ").append(unit).append(" must run standalone with no external project files.\n");
        sb.append("- Comment richly: put a clear comment before each logical step explaining its intent.\n");
        sb.append("- Robust error handling: never abort hard without a clear, actionable message")
            .append(lang.isDeclarative()
                ? " (failing tasks must carry descriptive messages).\n"
                : " and a non-zero exit code.\n");
        // The metadata header (script name / author / date / source / originating request) is injected
        // deterministically by the app AFTER generation. The model must never emit it — doing so
        // previously produced a duplicate header. In every mode except NONE the model still writes a
        // short FUNCTIONAL description of what the artefact does.
        sb.append("- Do NOT emit any metadata header or title block — no script name, author, creator,"
            + " date/time, 'Generated by', or the originating request anywhere. That header is added"
            + " automatically.\n");
        if (headerMode != HeaderMode.NONE) {
            sb.append("- Begin the body with a concise description block (1-5 lines) using the language's"
                + " idiomatic doc comment, stating WHAT the ").append(unit).append(" does and its main"
                + " steps. Describe behavior only; include none of the metadata listed above.\n");
        }
        sb.append("- Do NOT invent secrets, hostnames, or credentials; use clearly-commented variables/placeholders.\n");
        sb.append("\n").append(lang.displayName().toUpperCase(Locale.ROOT)).append(" IDIOMS:\n")
            .append(languageIdioms(lang)).append("\n");
        String rules = optionRules(lang, options);
        if (!rules.isBlank()) {
            sb.append("\nADDITIONAL REQUIREMENTS:\n").append(rules).append("\n");
        }
        return sb.toString().strip();
    }

    public static String buildUserPrompt(ScriptLanguage lang, HeaderFacts facts, WorkflowContext ctx,
                                         EnumSet<HardeningOption> opts, String extraInstructions) {
        return buildUserPrompt(lang, facts, ctx, opts, extraInstructions, HeaderMode.AUTO);
    }

    /**
     * @param headerMode AUTO/CUSTOM: the model is told NOT to emit metadata (the app injects the header
     *                   afterwards) and to begin with a functional description; the originating request
     *                   is supplied only as context. NONE: no description is requested either.
     */
    public static String buildUserPrompt(ScriptLanguage lang, HeaderFacts facts, WorkflowContext ctx,
                                         EnumSet<HardeningOption> opts, String extraInstructions, HeaderMode headerMode) {
        String artefact = lang.isDeclarative() ? "Ansible playbook" : (lang.displayName() + " script");
        StringBuilder sb = new StringBuilder();
        sb.append("Generate the ").append(artefact).append(" now.\n\n");
        // No HEADER FACTS block: the metadata header is injected deterministically after generation, so
        // the model must not reproduce those fields (echoing them previously caused a duplicate header).
        sb.append("Do NOT emit any metadata header — no name, author, creator, date, 'Generated by',"
            + " or the originating request as header fields.\n");
        if (headerMode != HeaderMode.NONE) {
            sb.append("Begin with a short functional description of what the ").append(artefact)
                .append(" does and its main steps (behavior only, no metadata).\n");
        }
        sb.append("Originating request (context for the description; do not copy as a metadata field): ")
            .append(nz(facts.sourcePrompt())).append("\n\n");
        sb.append("TARGET LANGUAGE: ").append(lang.displayName()).append("\n\n");
        if (notBlank(extraInstructions)) {
            sb.append("ADDITIONAL USER INSTRUCTIONS:\n").append(extraInstructions.strip()).append("\n\n");
        }
        sb.append("WORK TO REPRODUCE (executed commands and outcomes from the agent run):\n");
        sb.append(ctx != null ? ctx.markdown() : "").append("\n");
        if (ctx != null && ctx.truncated()) {
            sb.append("\n(Note: the run was long; only the first ").append(ctx.includedActions())
                .append(" of ").append(ctx.totalActions())
                .append(" actions are shown in full — infer the remaining steps reasonably.)\n");
        }
        return sb.toString().strip();
    }

    /** Per-language strict-mode / error-handling idioms required by the generated artefact. */
    public static String languageIdioms(ScriptLanguage lang) {
        return switch (lang) {
            case BASH -> String.join("\n",
                "- Start the body with: set -euo pipefail",
                "- Set IFS=$'\\n\\t'.",
                "- Install an ERR trap that reports the failing line number and command, plus an EXIT trap for cleanup.",
                "- Quote ALL variable expansions (\"$var\").",
                "- Verify every required external command exists with: command -v <cmd>.",
                "- Use functions; send diagnostics to stderr; return meaningful non-zero exit codes.");
            case PYTHON -> String.join("\n",
                "- Put the logic in a main() function and guard with: if __name__ == \"__main__\": sys.exit(main()).",
                "- Use the logging module (to stderr) for diagnostics instead of bare print.",
                "- Wrap risky operations in try/except, catch specific exceptions, and exit via sys.exit(code).",
                "- Never let an unhandled traceback be the only error message.",
                "- When shelling out use subprocess with check=True and handle CalledProcessError.");
            case PERL -> String.join("\n",
                "- Begin with: use strict; use warnings;  (add use autodie; where it helps).",
                "- Wrap risky calls in eval { ... }; and inspect $@ for errors.",
                "- Use die/warn with descriptive context; set explicit exit() codes.",
                "- Check the return value of system()/open() calls.");
            case RUBY -> String.join("\n",
                "- Wrap the main flow in begin/rescue => e/ensure; rescue specific error classes.",
                "- Send diagnostics to STDERR; use abort(msg) or exit(code) with meaningful codes.",
                "- Verify the success of system() calls and external commands.");
            case POWERSHELL -> String.join("\n",
                "- Start with: Set-StrictMode -Version Latest  and  $ErrorActionPreference = 'Stop'.",
                "- Use [CmdletBinding()] and a param() block for inputs.",
                "- Wrap risky work in try/catch/finally and use throw for fatal errors (optionally a trap block).",
                "- After native commands, check $LASTEXITCODE; exit with meaningful codes.",
                "- Write diagnostics with Write-Error/Write-Verbose, not only Write-Host.");
            case WINDOWS_CMD -> String.join("\n",
                "- Start with: @echo off  then  setlocal EnableExtensions EnableDelayedExpansion.",
                "- After each critical command check errors: 'if errorlevel 1 ...' or 'if %ERRORLEVEL% neq 0 ...', then 'exit /b <code>' with a meaningful code.",
                "- Quote paths/values that may contain spaces (\"%VAR%\"); use delayed expansion (!VAR!) inside blocks.",
                "- Use 'call :label' for subroutines and 'goto :eof' / 'exit /b' to return; send diagnostics to stderr with '1>&2'.",
                "- Use pure cmd.exe built-ins; do NOT rely on PowerShell-, bash- or Unix-only commands.");
            case APPLESCRIPT -> String.join("\n",
                "- Wrap risky logic in 'try ... on error errMsg number errNum ... end try' and report a clear message.",
                "- Run shell commands with 'do shell script \"...\"'; build arguments safely with 'quoted form of'.",
                "- Log progress with 'log'; signal fatal failures with 'error \"message\" number <code>'.",
                "- Keep 'tell application \"...\"' blocks short and only where needed.",
                "- The script is run via osascript; on failure raise an error (non-zero) rather than returning silently.");
            case ANSIBLE -> String.join("\n",
                "- A single self-contained playbook in valid YAML, runnable with: ansible-playbook <file>.",
                "- Begin the file with '---'.",
                "- Use block/rescue/always for error handling; use assert and failed_when to validate state.",
                "- Prefer idempotent modules (apt, copy, template, service, ...) over command/shell; "
                    + "when using command/shell add creates/removes.",
                "- Set any_errors_fatal where appropriate; define a vars: section for all literals.",
                "- Every task needs a descriptive 'name:'; the playbook must be re-runnable without side effects.");
        };
    }

    private static String optionRules(ScriptLanguage lang, EnumSet<HardeningOption> opts) {
        return hardeningRulesText(opts, lang.isDeclarative());
    }

    /**
     * Renders the selected {@link HardeningOption}s as newline-separated prompt rules, reusable outside
     * the workflow-script generator (e.g. the snippet editor's "improve robustness" / custom improvement).
     * {@code declarative} switches to Ansible-style phrasing; pass {@code false} for imperative scripts.
     */
    public static String hardeningRulesText(EnumSet<HardeningOption> opts, boolean declarative) {
        boolean ansible = declarative;
        List<String> rules = new ArrayList<>();
        if (opts == null || opts.isEmpty()) {
            return "";
        }
        if (opts.contains(HardeningOption.STRICT_MODE)) {
            rules.add(ansible
                ? "- Validate prerequisites with assert/failed_when so bad state fails the play immediately."
                : "- Enable the language's strict / abort-on-error mode.");
        }
        if (opts.contains(HardeningOption.ERROR_TRAP_CLEANUP)) {
            rules.add(ansible
                ? "- Use block/rescue/always so failures are caught and cleanup always runs."
                : "- Add an error trap / finally / ensure block that reports failures and cleans up temporary state.");
        }
        if (opts.contains(HardeningOption.MEANINGFUL_EXIT_CODES)) {
            rules.add(ansible
                ? "- Make failing tasks stop the play with a clear message (any_errors_fatal where sensible)."
                : "- Use distinct, documented non-zero exit codes for distinct failure classes.");
        }
        if (opts.contains(HardeningOption.LOGGING_VERBOSE)) {
            rules.add(ansible
                ? "- Use the debug module for progress output (visible with -v)."
                : "- Emit timestamped log messages to stderr and support a --verbose/-v flag.");
        }
        if (opts.contains(HardeningOption.CONFIG_BLOCK)) {
            rules.add(ansible
                ? "- Hoist all literals (paths, hosts, packages) into a vars: block at the top."
                : "- Hoist all literals (paths, hosts, packages) into a clearly commented configuration block near the top.");
        }
        if (opts.contains(HardeningOption.END_SUMMARY)) {
            rules.add(ansible
                ? "- End with a debug summary of what changed."
                : "- Print a final summary of what was done (with success/failure counts).");
        }
        if (opts.contains(HardeningOption.STYLE_GUIDE_CLEAN)) {
            rules.add(ansible
                ? "- Follow ansible-lint conventions and use fully-qualified module names."
                : "- Follow the language style guide and keep it linter-clean (e.g. ShellCheck for bash).");
        }
        if (opts.contains(HardeningOption.PRECONDITION_CHECKS)) {
            rules.add(ansible
                ? "- Add pre_tasks/assert checks for required privileges, packages and connectivity before any change."
                : "- Before doing work, verify required commands, privileges (root/sudo) and connectivity.");
        }
        if (opts.contains(HardeningOption.IDEMPOTENCY)) {
            rules.add(ansible
                ? "- Ensure the playbook is fully idempotent (safe to re-run; rely on module idempotency and creates/removes)."
                : "- Detect already-completed steps and skip them so the script is safe to re-run.");
        }
        if (opts.contains(HardeningOption.SAFE_MODE)) {
            rules.add(ansible
                ? "- Support check mode (--check) and guard destructive tasks so a dry run makes no changes."
                : "- Support a --dry-run flag that prints intended actions without executing, and confirm before "
                    + "destructive operations (suppressible with --yes).");
        }
        if (opts.contains(HardeningOption.HELP_USAGE)) {
            rules.add(ansible
                ? "- Document all variables and how to override them via --extra-vars at the top of the file."
                : "- Provide a --help/usage message and parse command-line arguments for the configurable values.");
        }
        return String.join("\n", rules);
    }

    // ------------------------------------------------------------------ multi-host (swarm) assembly

    /** Non-secret host facts fed into a multi-host workflow script. Carries no passwords/key contents. */
    public record SwarmHost(String name, String host, int port, String user, String group,
                            String authMethod, String keyPath, String jumpHostSpec) {
    }

    /** Multi-host orchestration options for swarm workflow scripts (separate from {@link HardeningOption}). */
    public enum SwarmScriptOption {
        HOST_LIST_EMBEDDED,
        EXTERNAL_HOST_FILE,
        PARALLEL_FANOUT,
        MAX_PARALLELISM,
        PER_HOST_TIMEOUT,
        CONTINUE_ON_ERROR,
        PER_HOST_RESULT_COLLECTION,
        AGGREGATED_REPORT,
        RETRY_BACKOFF,
        SSH_OPTIONS,
        JUMP_HOST,
        DRY_RUN,
        SUDO_ESCALATION;

        /** A safe default set: embed hosts, sane SSH options, per-host timeout, keep going, collect + report. */
        public static EnumSet<SwarmScriptOption> defaults() {
            return EnumSet.of(HOST_LIST_EMBEDDED, SSH_OPTIONS, PER_HOST_TIMEOUT,
                CONTINUE_ON_ERROR, PER_HOST_RESULT_COLLECTION, AGGREGATED_REPORT);
        }
    }

    public static String buildSwarmSystemPrompt(ScriptLanguage lang, EnumSet<HardeningOption> hardening,
                                                EnumSet<SwarmScriptOption> swarmOpts, HeaderMode headerMode) {
        EnumSet<SwarmScriptOption> options = swarmOpts != null ? swarmOpts : SwarmScriptOption.defaults();
        StringBuilder sb = new StringBuilder(buildSystemPrompt(lang, hardening, headerMode));
        sb.append("\n\nMULTI-HOST ORCHESTRATION:\n");
        if (lang.isDeclarative()) {
            sb.append("- Target multiple hosts: define them in the play's hosts/inventory and run the per-host tasks on each.\n");
        } else {
            sb.append("- The ").append(lang.displayName())
                .append(" artefact must iterate a list of hosts and run the per-host work on EACH host over SSH.\n");
        }
        sb.append("- NEVER embed passwords or private-key contents. Use key files, ssh-agent, or a vault/--extra-vars variable.\n");
        String rules = swarmOptionRules(lang, options);
        if (!rules.isBlank()) {
            sb.append(rules).append("\n");
        }
        return sb.toString().strip();
    }

    public static String buildSwarmUserPrompt(ScriptLanguage lang, HeaderFacts facts, WorkflowContext perHostWork,
                                              List<SwarmHost> hosts, EnumSet<SwarmScriptOption> swarmOpts,
                                              String extraInstructions, HeaderMode headerMode) {
        StringBuilder sb = new StringBuilder(
            buildUserPrompt(lang, facts, perHostWork, null, extraInstructions, headerMode));
        sb.append("\n\nTARGET HOSTS (non-secret facts; reference key files / ssh-agent, never embed secrets):\n");
        if (hosts == null || hosts.isEmpty()) {
            sb.append("(no hosts provided — read the host list from a file or --extra-vars)\n");
        } else {
            for (SwarmHost host : hosts) {
                sb.append("- ").append(nz(host.name())).append(": ")
                    .append(nz(host.user())).append("@").append(nz(host.host())).append(":").append(host.port());
                if (notBlank(host.group())) {
                    sb.append("  group=").append(host.group());
                }
                if (notBlank(host.authMethod())) {
                    sb.append("  auth=").append(host.authMethod());
                }
                if (notBlank(host.keyPath())) {
                    sb.append("  key=").append(host.keyPath());
                }
                if (notBlank(host.jumpHostSpec())) {
                    sb.append("  jump=").append(host.jumpHostSpec());
                }
                sb.append("\n");
            }
        }
        return sb.toString().strip();
    }

    private static String swarmOptionRules(ScriptLanguage lang, EnumSet<SwarmScriptOption> opts) {
        boolean ansible = lang.isDeclarative();
        List<String> rules = new ArrayList<>();
        if (opts.contains(SwarmScriptOption.HOST_LIST_EMBEDDED)) {
            rules.add(ansible
                ? "- Define the inventory/hosts inline in the playbook (a vars host list or inventory block)."
                : "- Embed the host list as an array in the configuration block at the top.");
        }
        if (opts.contains(SwarmScriptOption.EXTERNAL_HOST_FILE)) {
            rules.add(ansible
                ? "- Read hosts from an external inventory file passed with -i."
                : "- Read the host list from an external file (one host per line / CSV) given as an argument.");
        }
        if (opts.contains(SwarmScriptOption.PARALLEL_FANOUT)) {
            rules.add(ansible
                ? "- Increase parallelism via forks so hosts are processed concurrently."
                : "- Process hosts in parallel (e.g. xargs -P / GNU parallel / a thread pool).");
        }
        if (opts.contains(SwarmScriptOption.MAX_PARALLELISM)) {
            rules.add("- Cap concurrency to a configurable maximum number of simultaneous hosts.");
        }
        if (opts.contains(SwarmScriptOption.PER_HOST_TIMEOUT)) {
            rules.add(ansible
                ? "- Apply a connection/task timeout per host."
                : "- Wrap each host's work in a timeout and an SSH ConnectTimeout.");
        }
        if (opts.contains(SwarmScriptOption.CONTINUE_ON_ERROR)) {
            rules.add(ansible
                ? "- Continue to the remaining hosts when one fails (ignore_errors / max_fail_percentage)."
                : "- Continue to the next host when one fails; record the failure rather than aborting the whole run.");
        }
        if (opts.contains(SwarmScriptOption.PER_HOST_RESULT_COLLECTION)) {
            rules.add("- Collect each host's status/output into a structured result (table or CSV).");
        }
        if (opts.contains(SwarmScriptOption.AGGREGATED_REPORT)) {
            rules.add("- Print an aggregated end-of-run report (OK / FAILED / UNREACHABLE counts and per-host lines).");
        }
        if (opts.contains(SwarmScriptOption.RETRY_BACKOFF)) {
            rules.add("- Retry each host a few times with exponential backoff before marking it failed.");
        }
        if (opts.contains(SwarmScriptOption.SSH_OPTIONS)) {
            rules.add(ansible
                ? "- Use key/agent auth; set ansible_ssh_common_args for BatchMode and ConnectTimeout."
                : "- Centralize SSH options: key file / ssh-agent, BatchMode=yes, ConnectTimeout, and a StrictHostKeyChecking policy.");
        }
        if (opts.contains(SwarmScriptOption.JUMP_HOST)) {
            rules.add(ansible
                ? "- Route through a bastion via ansible_ssh_common_args ProxyJump."
                : "- Support routing through a jump/bastion host (ssh -J / ProxyJump).");
        }
        if (opts.contains(SwarmScriptOption.DRY_RUN)) {
            rules.add(ansible
                ? "- Support --check so a dry run makes no changes."
                : "- Support a --dry-run flag that prints intended per-host actions without connecting.");
        }
        if (opts.contains(SwarmScriptOption.SUDO_ESCALATION)) {
            rules.add(ansible
                ? "- Use become for privilege escalation where needed."
                : "- Support optional privilege escalation per host (sudo -n) where needed.");
        }
        return String.join("\n", rules);
    }

    // ------------------------------------------------------------------ output post-processing

    /**
     * Removes a wrapping markdown code fence (```lang … ```), if present, and normalizes line
     * endings to LF. When the model appends prose after the closing fence, that closing fence line
     * and everything after it is dropped, so the returned text is just the script body.
     */
    public static String stripCodeFences(String aiOutput) {
        if (aiOutput == null) {
            return "";
        }
        String text = aiOutput.replace("\r\n", "\n").replace("\r", "\n").strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String firstLine = text.substring(0, firstNewline).strip();
        if (!firstLine.matches("```[A-Za-z0-9_+-]*")) {
            return text;
        }
        String body = text.substring(firstNewline + 1);
        StringBuilder kept = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.strip().equals("```")) {
                break; // closing fence reached — drop it and any trailing prose
            }
            kept.append(line).append("\n");
        }
        return kept.toString().strip();
    }

    /**
     * Guarantees the required header (name/author/date) is present. If the model already produced
     * one (date + creator visible near the top) the script is returned unchanged; otherwise a
     * deterministic header comment block is injected right after the shebang / {@code ---} line.
     */
    public static String ensureHeaderInjected(String script, ScriptLanguage lang, HeaderFacts facts) {
        String content = script == null ? "" : script.stripTrailing();
        if (headerPresent(content, lang, facts)) {
            return content;
        }
        return insertHeaderAfterLead(content, lang, buildHeaderComment(lang, facts));
    }

    /**
     * Prepends a user-defined header template (already variable-substituted) after the shebang /
     * {@code ---} line, replacing the auto-generated header. A blank header leaves the script as-is.
     */
    public static String injectHeaderOverride(String script, ScriptLanguage lang, String headerText) {
        String content = script == null ? "" : script.stripTrailing();
        String header = headerText == null ? "" : headerText.strip();
        if (header.isEmpty()) {
            return content;
        }
        return insertHeaderAfterLead(content, lang, header);
    }

    /**
     * Inserts {@code header} directly after the artefact's mandatory lead line (shebang, {@code ---},
     * or {@code @echo off}), or prepends the lead line + header when it is missing.
     */
    private static String insertHeaderAfterLead(String content, ScriptLanguage lang, String header) {
        int nl = content.indexOf('\n');
        String firstLine = nl >= 0 ? content.substring(0, nl) : content;
        String rest = nl >= 0 ? content.substring(nl + 1) : "";
        boolean firstIsLead;
        if (lang.isDeclarative()) {
            firstIsLead = firstLine.strip().equals("---");
        } else if (lang == ScriptLanguage.WINDOWS_CMD) {
            firstIsLead = firstLine.strip().equalsIgnoreCase("@echo off");
        } else {
            firstIsLead = firstLine.startsWith("#!");
        }
        if (firstIsLead) {
            return firstLine + "\n" + header + "\n" + rest;
        }
        String lead = lang.leadLine();
        if (lead != null) {
            return lead + "\n" + header + "\n" + content;
        }
        return header + "\n" + content;
    }

    /**
     * Detects an already-present header. To avoid false positives (the creator/date also occur in
     * ordinary commands such as {@code /home/<user>} paths or {@code user@host} targets), both the
     * creator and the date must appear on comment lines near the top. When in doubt we return false
     * so the deterministic header is injected — a duplicate header is far less harmful than none.
     */
    private static boolean headerPresent(String content, ScriptLanguage lang, HeaderFacts facts) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String creator = facts.creatorUser();
        if (creator == null || creator.strip().length() < 2) {
            return false;
        }
        String commentPrefix = lang.commentPrefix();
        String date = DATE.format(facts.generatedAt());
        String head = content.length() > HEADER_DETECTION_WINDOW
            ? content.substring(0, HEADER_DETECTION_WINDOW)
            : content;
        boolean creatorOnComment = false;
        boolean dateOnComment = false;
        for (String rawLine : head.split("\n", -1)) {
            String line = rawLine.strip();
            if (!line.startsWith(commentPrefix)) {
                continue;
            }
            if (line.contains(creator)) {
                creatorOnComment = true;
            }
            if (line.contains(date)) {
                dateOnComment = true;
            }
        }
        return creatorOnComment && dateOnComment;
    }

    private static String buildHeaderComment(ScriptLanguage lang, HeaderFacts facts) {
        String p = lang.commentPrefix();
        String bar = p + " " + "=".repeat(60);
        StringBuilder sb = new StringBuilder();
        sb.append(bar).append("\n");
        sb.append(p).append(" Script:  ").append(nz(facts.scriptName())).append("\n");
        String author = nz(facts.creatorUser());
        if (notBlank(facts.sshUser()) || notBlank(facts.connectionName())) {
            author += " (local) — executed as " + nz(facts.sshUser()) + "@" + nz(facts.connectionName());
        }
        sb.append(p).append(" Author:  ").append(author).append("\n");
        sb.append(p).append(" Created: ").append(TIMESTAMP.format(facts.generatedAt())).append("\n");
        sb.append(p).append(" Source:  Generated by KorTTY AI (").append(nz(facts.aiProfileName())).append(")\n");
        String request = nz(facts.sourcePrompt()).replaceAll("\\s+", " ");
        if (request.length() > MAX_HEADER_REQUEST_CHARS) {
            request = request.substring(0, MAX_HEADER_REQUEST_CHARS) + "…";
        }
        sb.append(p).append(" Request: ").append(request).append("\n");
        sb.append(bar);
        return sb.toString();
    }

    /**
     * Maps a probed OS string (e.g. "Fedora Linux 44 (Workstation Edition)", "Ubuntu 22.04", "Darwin")
     * to one of the user's configured System-list entries (any Linux distro collapses to "Linux").
     * Returns the matching list entry verbatim, or {@code null} when no list entry applies — so only
     * names actually present in the System list are ever used.
     */
    public static String matchOperatingSystem(String rawOs, java.util.List<String> systemList) {
        if (rawOs == null || rawOs.isBlank() || systemList == null || systemList.isEmpty()) {
            return null;
        }
        String lower = rawOs.toLowerCase(Locale.ROOT);
        String canonical;
        if (lower.contains("windows") || lower.contains("mingw") || lower.contains("msys") || lower.contains("cygwin")) {
            canonical = "windows";
        } else if (lower.contains("darwin") || lower.contains("mac os") || lower.contains("macos") || lower.contains("os x")) {
            canonical = "macos";
        } else if (lower.contains("linux") || lower.contains("ubuntu") || lower.contains("debian")
                || lower.contains("fedora") || lower.contains("red hat") || lower.contains("redhat")
                || lower.contains("rhel") || lower.contains("centos") || lower.contains("suse")
                || lower.contains("arch") || lower.contains("alpine") || lower.contains("rocky")
                || lower.contains("almalinux") || lower.contains("gentoo") || lower.contains("manjaro")
                || lower.contains("mint")) {
            canonical = "linux";
        } else {
            return null;
        }
        for (String entry : systemList) {
            if (entry == null) {
                continue;
            }
            String normalized = entry.toLowerCase(Locale.ROOT).replace(" ", "");
            boolean matches = switch (canonical) {
                case "windows" -> normalized.equals("windows");
                case "macos" -> normalized.equals("macos") || normalized.equals("osx") || normalized.equals("mac");
                case "linux" -> normalized.equals("linux");
                default -> false;
            };
            if (matches) {
                return entry;
            }
        }
        return null;
    }

    /** Builds a filesystem-friendly default script name from the originating request (underscores only). */
    /**
     * Filler words (articles, prepositions, conjunctions, polite words and generic request verbs,
     * English and German) dropped when deriving a short script name so the name starts with the
     * meaningful part of the prompt.
     */
    private static final Set<String> SCRIPT_NAME_FILLER_WORDS = Set.of(
        // English
        "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "with", "by", "from",
        "please", "me", "my", "all", "show", "display", "get", "fetch", "find", "print", "output", "give",
        // German
        "die", "der", "das", "den", "dem", "ein", "eine", "einen", "im", "von", "auf", "und", "oder",
        "mit", "bitte", "mir", "mein", "meine", "alle", "zeige", "zeig", "anzeigen", "gib", "finde", "hole");

    private static final int SCRIPT_NAME_MAX_WORDS = 3;
    private static final int SCRIPT_NAME_MAX_LENGTH = 28;

    public static String defaultScriptName(String sourcePrompt, ScriptLanguage lang) {
        return buildShortScriptStem(sourcePrompt) + lang.fileExtension();
    }

    /**
     * Builds a short, file-name-safe stem (lowercase, underscores) from the prompt: it keeps only the
     * first few meaningful words so the generated script name stays short rather than echoing the
     * whole request. Falls back to {@code workflow_script} when nothing usable remains.
     */
    static String buildShortScriptStem(String sourcePrompt) {
        String normalized = sourcePrompt == null ? "" : sourcePrompt.strip().toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                words.add(token);
            }
        }
        List<String> meaningful = new ArrayList<>();
        for (String word : words) {
            if (!SCRIPT_NAME_FILLER_WORDS.contains(word)) {
                meaningful.add(word);
            }
        }
        // If filtering removed everything (prompt was all filler words), keep the raw words.
        List<String> source = meaningful.isEmpty() ? words : meaningful;
        StringBuilder stem = new StringBuilder();
        int used = 0;
        for (String word : source) {
            if (used >= SCRIPT_NAME_MAX_WORDS) {
                break;
            }
            int extra = stem.length() == 0 ? word.length() : word.length() + 1;
            if (stem.length() > 0 && stem.length() + extra > SCRIPT_NAME_MAX_LENGTH) {
                break;
            }
            if (stem.length() > 0) {
                stem.append('_');
            }
            stem.append(word);
            used++;
        }
        // Hard-cap a single very long first word and trim any trailing underscore from truncation.
        if (stem.length() > SCRIPT_NAME_MAX_LENGTH) {
            stem.setLength(SCRIPT_NAME_MAX_LENGTH);
        }
        while (stem.length() > 0 && stem.charAt(stem.length() - 1) == '_') {
            stem.setLength(stem.length() - 1);
        }
        return stem.length() == 0 ? "workflow_script" : stem.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
