package de.kortty.core;

import de.kortty.core.WorkflowScriptSupport.ScriptLanguage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, UI-free detection of mixed-language scripts, and of the orchestration formats in
 * which a foreign language is not a defect but the design.
 *
 * <p>It answers two questions the AI language migration needs before it may offer anything:
 * <ol>
 *   <li><b>Is this a host document?</b> An Azure DevOps pipeline, a GitHub Actions workflow, a
 *       Jenkinsfile, an Ansible playbook and friends invoke Bash or PowerShell <em>by
 *       construction</em>. Such a document must never be reported as "mixed", and must never be the
 *       target of a whole-script migration. Only the bodies of its script steps are candidates, and
 *       only when those steps disagree among themselves.</li>
 *   <li><b>Otherwise: does this plain script embed another language?</b> A Bash frame that pipes a
 *       heredoc into Perl, an inline {@code awk} program, a {@code python3 -c} one-liner.</li>
 * </ol>
 *
 * <p>All methods are pure so they can be unit-tested without the JavaFX toolkit and without a model
 * call. The AI is asked for a second opinion inside the full code analysis; this class decides
 * whether the option is offered at all.
 */
public final class ScriptLanguageMixSupport {

    /**
     * Orchestration formats that legitimately embed foreign script languages. Their own body is
     * never migrated language-wise; they can only be converted into another such format, and only
     * when the user explicitly asks for it.
     */
    public enum HostFormat {
        NONE(null, null, null, false),
        AZURE_PIPELINES("Azure DevOps Pipeline", "yaml", "azure-pipelines.yml", true),
        GITHUB_ACTIONS("GitHub Actions Workflow", "yaml", "workflow.yml", true),
        GITLAB_CI("GitLab CI", "yaml", ".gitlab-ci.yml", true),
        JENKINS_DECLARATIVE("Jenkinsfile (declarative)", "groovy", "Jenkinsfile", true),
        JENKINS_SCRIPTED("Jenkinsfile (scripted)", "groovy", "Jenkinsfile", true),
        ANSIBLE("Ansible-Playbook", "yaml", "playbook.yml", true),
        PUPPET("Puppet-Manifest", "puppet", "manifest.pp", true),
        // An image build recipe, not a CI pipeline: converting it to or from one has no meaning.
        DOCKERFILE("Dockerfile", "dockerfile", "Dockerfile", false);

        private final String displayName;
        private final String snippetLanguage;
        private final String defaultFileName;
        private final boolean conversionTarget;

        HostFormat(String displayName, String snippetLanguage, String defaultFileName,
                   boolean conversionTarget) {
            this.displayName = displayName;
            this.snippetLanguage = snippetLanguage;
            this.defaultFileName = defaultFileName;
            this.conversionTarget = conversionTarget;
        }

        public String displayName() {
            return displayName;
        }

        /** The snippet language token the editor uses for this format, {@code null} for {@link #NONE}. */
        public String snippetLanguage() {
            return snippetLanguage;
        }

        public String defaultFileName() {
            return defaultFileName;
        }

        /** Whether a host document may be converted <em>into</em> this format. */
        public boolean isConversionTarget() {
            return conversionTarget;
        }

        /** The formats offered as a conversion target, in declaration order. */
        public static List<HostFormat> conversionTargets() {
            List<HostFormat> targets = new ArrayList<>();
            for (HostFormat format : values()) {
                if (format.isConversionTarget()) {
                    targets.add(format);
                }
            }
            return List.copyOf(targets);
        }
    }

    /** What a migration may do to a given document. */
    public enum MigrationMode {
        /** Plain script embedding foreign languages: rewrite the whole file in one target language. */
        WHOLE_SCRIPT,
        /** Host document whose script steps disagree: unify only the step bodies, keep the scaffold. */
        EMBEDDED_STEPS_ONLY,
        /** Host document converted into another platform's schema. Only ever on explicit user choice. */
        HOST_FORMAT_CONVERSION,
        /** Nothing to offer. */
        UNAVAILABLE
    }

    /**
     * One stretch of foreign language: an embedded block in a plain script, or the body of one
     * script step in a host document. Line numbers are 1-based and inclusive.
     */
    public record EmbeddedLanguage(String language, int startLine, int endLine, String trigger) {
    }

    /**
     * @param hostFormat       the detected orchestration format, or {@link HostFormat#NONE}
     * @param dominantLanguage the script's own language; for a host document its scaffold language
     * @param embedded         foreign blocks, or the script-step bodies of a host document
     */
    public record LanguageMix(HostFormat hostFormat, String dominantLanguage,
                              List<EmbeddedLanguage> embedded) {

        public LanguageMix {
            hostFormat = hostFormat != null ? hostFormat : HostFormat.NONE;
            embedded = embedded != null ? List.copyOf(embedded) : List.of();
        }

        /** The distinct languages found, in encounter order. */
        public Set<String> embeddedLanguages() {
            Set<String> languages = new LinkedHashSet<>();
            for (EmbeddedLanguage entry : embedded) {
                languages.add(entry.language());
            }
            return Collections.unmodifiableSet(languages);
        }

        /** The languages of a host document's script steps; empty for a plain script. */
        public Set<String> stepLanguages() {
            return hostFormat == HostFormat.NONE ? Set.of() : embeddedLanguages();
        }

        /**
         * What may be offered without the user asking for anything. This never returns
         * {@link MigrationMode#HOST_FORMAT_CONVERSION}: a platform change is always a deliberate
         * choice, never a suggestion.
         */
        public MigrationMode defaultMode() {
            if (hostFormat != HostFormat.NONE) {
                return stepLanguages().size() > 1
                    ? MigrationMode.EMBEDDED_STEPS_ONLY
                    : MigrationMode.UNAVAILABLE;
            }
            return embedded.isEmpty() ? MigrationMode.UNAVAILABLE : MigrationMode.WHOLE_SCRIPT;
        }
    }

    private ScriptLanguageMixSupport() {
    }

    // ------------------------------------------------------------------ entry points

    /** Detects the host format first; only a non-host document is scanned for embedded languages. */
    public static LanguageMix detect(String declaredLanguage, String content) {
        String text = content != null ? content : "";
        String[] lines = splitLines(text);
        HostFormat host = detectHostFormat(declaredLanguage, text);
        if (host != HostFormat.NONE) {
            return new LanguageMix(host, host.snippetLanguage(), detectHostSteps(host, lines));
        }
        String dominant = SnippetLanguageSupport.detectSnippetLanguage(declaredLanguage, text);
        return new LanguageMix(HostFormat.NONE, dominant, detectEmbeddedInScript(dominant, lines));
    }

    /**
     * The orchestration format this document is written in, or {@link HostFormat#NONE}. Also used to
     * verify that a platform conversion actually reached the requested target format.
     */
    public static HostFormat detectHostFormat(String declaredLanguage, String content) {
        String text = content != null ? content : "";
        if (text.isBlank()) {
            return HostFormat.NONE;
        }
        String[] lines = splitLines(text);
        String declared = SnippetLanguageSupport.normalizeSnippetLanguage(declaredLanguage);

        if ("dockerfile".equals(declared)
            || (anyLineMatches(lines, DOCKERFILE_FROM) && anyLineMatches(lines, DOCKERFILE_STEP))) {
            return HostFormat.DOCKERFILE;
        }
        if (anyLineMatches(lines, JENKINS_DECLARATIVE_ROOT)) {
            return HostFormat.JENKINS_DECLARATIVE;
        }
        if (anyLineMatches(lines, JENKINS_SCRIPTED_ROOT) && anyLineMatches(lines, JENKINS_STAGE)) {
            return HostFormat.JENKINS_SCRIPTED;
        }
        if (anyLineMatches(lines, PUPPET_EXEC) || anyLineMatches(lines, PUPPET_RESOURCE)) {
            return HostFormat.PUPPET;
        }
        // The YAML family. runs-on: is unique to GitHub Actions, so it is decided first; Azure is
        // then the only one with step verbs or an agent pool.
        if (hasTopLevelKey(lines, "jobs") && anyLineMatches(lines, GITHUB_RUNS_ON)) {
            return HostFormat.GITHUB_ACTIONS;
        }
        if (anyLineMatches(lines, AZURE_STEP)
            || ((hasTopLevelKey(lines, "pool") || hasTopLevelKey(lines, "trigger")
                || hasTopLevelKey(lines, "stages")) && anyLineMatches(lines, YAML_STEPS_KEY))) {
            return HostFormat.AZURE_PIPELINES;
        }
        if (anyLineMatches(lines, ANSIBLE_MODULE)
            || ((hasTopLevelKey(lines, "hosts") || anyLineMatches(lines, ANSIBLE_PLAY_ITEM))
                && anyLineMatches(lines, ANSIBLE_TASKS_KEY))) {
            return HostFormat.ANSIBLE;
        }
        if (hasTopLevelKey(lines, "stages") && anyLineMatches(lines, GITLAB_SCRIPT_KEY)) {
            return HostFormat.GITLAB_CI;
        }
        return HostFormat.NONE;
    }

    /**
     * True when a steps-only migration left the host document's scaffold intact: every line outside a
     * script-step body is unchanged, apart from the step verb itself (a {@code - bash:} step that
     * became {@code - pwsh:}, a {@code shell:} value, an {@code sh} call that became {@code powershell}).
     *
     * <p>This is what makes the steps-only mode trustworthy. A pipeline whose triggers, pools,
     * conditions or task versions were quietly rewritten is a different pipeline, and the user asked
     * only for its script steps to be unified — so a result that fails this check is discarded rather
     * than offered.
     *
     * <p>Line counts may differ freely: a rewritten step body is normally longer or shorter than the
     * original, so the scaffold is compared as a sequence, not positionally.
     */
    public static boolean scaffoldPreserved(HostFormat host, String original, String migrated) {
        if (host == null || host == HostFormat.NONE) {
            return true;
        }
        return scaffoldLines(host, original).equals(scaffoldLines(host, migrated));
    }

    private static List<String> scaffoldLines(HostFormat host, String content) {
        String[] lines = splitLines(content != null ? content : "");
        boolean[] insideStep = new boolean[lines.length];
        for (EmbeddedLanguage step : detectHostSteps(host, lines)) {
            for (int line = step.startLine(); line <= step.endLine() && line <= lines.length; line++) {
                insideStep[line - 1] = true;
            }
        }
        List<String> scaffold = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            // Blank-line drift is formatting, not a structural change.
            if (insideStep[i] || lines[i].isBlank()) {
                continue;
            }
            scaffold.add(normalizeStepVerb(host, lines[i]));
        }
        return scaffold;
    }

    private static final Pattern YAML_STEP_VERB =
        Pattern.compile("^(\\s*-?\\s*)(script|bash|pwsh|powershell)(\\s*:)");
    private static final Pattern YAML_SHELL_VALUE =
        Pattern.compile("^(\\s*-?\\s*shell\\s*:\\s*)[A-Za-z0-9_.\\-]+\\s*$");
    private static final Pattern JENKINS_STEP_VERB =
        Pattern.compile("(?<![A-Za-z0-9_.])(sh|bat|powershell|pwsh)(\\s*\\(?\\s*['\"])");

    /** Replaces the language-selecting token of a step line, which a migration may legitimately change. */
    private static String normalizeStepVerb(HostFormat host, String line) {
        return switch (host) {
            case JENKINS_DECLARATIVE, JENKINS_SCRIPTED ->
                JENKINS_STEP_VERB.matcher(line).replaceAll("<STEP>$2");
            case AZURE_PIPELINES, GITHUB_ACTIONS, GITLAB_CI, ANSIBLE -> {
                String normalized = YAML_STEP_VERB.matcher(line).replaceFirst("$1<STEP>$3");
                yield YAML_SHELL_VALUE.matcher(normalized).replaceFirst("$1<SHELL>");
            }
            default -> line;
        };
    }

    // ------------------------------------------------------------------ host-format step scanning

    private static final Pattern DOCKERFILE_FROM = Pattern.compile("^\\s*FROM\\s+\\S");
    private static final Pattern DOCKERFILE_STEP =
        Pattern.compile("^\\s*(RUN|CMD|ENTRYPOINT|COPY|ADD)\\s");
    private static final Pattern DOCKERFILE_RUN = Pattern.compile("^\\s*RUN\\s+(.*)$");
    private static final Pattern JENKINS_DECLARATIVE_ROOT = Pattern.compile("^\\s*pipeline\\s*\\{");
    private static final Pattern JENKINS_SCRIPTED_ROOT = Pattern.compile("^\\s*node\\s*[({]");
    private static final Pattern JENKINS_STAGE = Pattern.compile("(?<![A-Za-z0-9_.])stage\\s*\\(");
    private static final Pattern JENKINS_STEP = Pattern.compile(
        "(?<![A-Za-z0-9_.])(sh|bat|powershell|pwsh)\\s*\\(?\\s*(?:script\\s*:\\s*)?('''|\"\"\"|'|\")");
    private static final Pattern PUPPET_EXEC =
        Pattern.compile("^\\s*exec\\s*\\{|^\\s*exec\\s*\\{\\s*$");
    private static final Pattern PUPPET_RESOURCE =
        Pattern.compile("^\\s*(class|define)\\s+[A-Za-z_][\\w:]*\\s*[({]");
    private static final Pattern PUPPET_COMMAND = Pattern.compile("^\\s*command\\s*=>\\s*(.*)$");
    private static final Pattern GITHUB_RUNS_ON = Pattern.compile("^\\s*runs-on\\s*:");
    private static final Pattern GITHUB_RUN_STEP = Pattern.compile("^(\\s*)(?:-\\s*)?(run)\\s*:(.*)$");
    private static final Pattern GITHUB_SHELL_KEY =
        Pattern.compile("^\\s*(?:-\\s*)?shell\\s*:\\s*([A-Za-z0-9_.\\-]+)");
    private static final Pattern AZURE_STEP =
        Pattern.compile("^\\s*-\\s*(task|script|bash|pwsh|powershell|checkout)\\s*:");
    private static final Pattern AZURE_SCRIPT_STEP =
        Pattern.compile("^(\\s*)-\\s*(script|bash|pwsh|powershell)\\s*:(.*)$");
    private static final Pattern YAML_STEPS_KEY = Pattern.compile("^\\s*steps\\s*:");
    private static final Pattern ANSIBLE_TASKS_KEY = Pattern.compile("^\\s*(tasks|pre_tasks|post_tasks)\\s*:");
    private static final Pattern ANSIBLE_PLAY_ITEM = Pattern.compile("^\\s*-\\s*hosts\\s*:");
    private static final Pattern ANSIBLE_MODULE = Pattern.compile("^\\s*(?:-\\s*)?ansible\\.builtin\\.\\w+\\s*:");
    private static final Pattern ANSIBLE_SHELL_STEP = Pattern.compile(
        "^(\\s*)(?:-\\s*)?(?:ansible\\.builtin\\.)?(shell|command|raw|script)\\s*:(.*)$");
    private static final Pattern GITLAB_SCRIPT_KEY =
        Pattern.compile("^\\s+(before_script|script|after_script)\\s*:");
    private static final Pattern GITLAB_SCRIPT_STEP =
        Pattern.compile("^(\\s*)(before_script|script|after_script)\\s*:(.*)$");

    /**
     * The script-step bodies of a host document. Every entry is a step body, never the scaffold —
     * that is what lets a caller rewrite step contents while leaving the document structure intact.
     */
    private static List<EmbeddedLanguage> detectHostSteps(HostFormat host, String[] lines) {
        return switch (host) {
            case AZURE_PIPELINES -> scanKeyedSteps(lines, AZURE_SCRIPT_STEP, ScriptLanguageMixSupport::azureStepLanguage);
            case GITHUB_ACTIONS -> scanGithubSteps(lines);
            case GITLAB_CI -> scanKeyedSteps(lines, GITLAB_SCRIPT_STEP, key -> "bash");
            case JENKINS_DECLARATIVE, JENKINS_SCRIPTED -> scanJenkinsSteps(lines);
            case ANSIBLE -> scanKeyedSteps(lines, ANSIBLE_SHELL_STEP, key -> "bash");
            case PUPPET -> scanSingleLineSteps(lines, PUPPET_COMMAND, "bash");
            case DOCKERFILE -> scanSingleLineSteps(lines, DOCKERFILE_RUN, "bash");
            case NONE -> List.of();
        };
    }

    /** {@code - script:} is the agent's default shell; only an explicit verb pins a language. */
    private static String azureStepLanguage(String verb) {
        return switch (verb.toLowerCase(Locale.ROOT)) {
            case "pwsh", "powershell" -> "powershell";
            default -> "bash";
        };
    }

    /**
     * Steps written as {@code <key>: <inline>} or {@code <key>: |} followed by an indented block.
     * The reported range covers the body only, so the key line itself stays part of the scaffold.
     */
    private static List<EmbeddedLanguage> scanKeyedSteps(String[] lines, Pattern stepPattern,
                                                         java.util.function.Function<String, String> languageOf) {
        List<EmbeddedLanguage> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = stepPattern.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            String verb = matcher.group(2);
            String inline = matcher.group(3) != null ? matcher.group(3).trim() : "";
            String language = languageOf.apply(verb);
            // The body of a block scalar is indented past the KEY, not past the list dash: using the
            // raw line indent would swallow the step's sibling keys (displayName, condition, ...).
            int keyIndent = matcher.start(2);
            if (isBlockScalarIndicator(inline)) {
                int end = blockBodyEndLine(lines, i, keyIndent);
                if (end > i + 1) {
                    found.add(new EmbeddedLanguage(language, i + 2, end, verb + ":"));
                }
            } else if (!inline.isEmpty()) {
                found.add(new EmbeddedLanguage(language, i + 1, i + 1, verb + ": " + shorten(inline)));
            }
        }
        return found;
    }

    private static List<EmbeddedLanguage> scanSingleLineSteps(String[] lines, Pattern pattern, String language) {
        List<EmbeddedLanguage> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.matches()) {
                int end = i + 1;
                // Honour backslash continuations (Dockerfile RUN, shell commands).
                while (end < lines.length && lines[end - 1].stripTrailing().endsWith("\\")) {
                    end++;
                }
                found.add(new EmbeddedLanguage(language, i + 1, end, shorten(matcher.group(1))));
            }
        }
        return found;
    }

    /** GitHub Actions pins the step language with a sibling {@code shell:} key; default is bash. */
    private static List<EmbeddedLanguage> scanGithubSteps(String[] lines) {
        List<EmbeddedLanguage> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = GITHUB_RUN_STEP.matcher(lines[i]);
            if (!matcher.matches()) {
                continue;
            }
            String inline = matcher.group(3) != null ? matcher.group(3).trim() : "";
            String language = githubShellLanguage(stepShell(lines, i));
            int keyIndent = matcher.start(2);
            if (isBlockScalarIndicator(inline)) {
                int end = blockBodyEndLine(lines, i, keyIndent);
                if (end > i + 1) {
                    found.add(new EmbeddedLanguage(language, i + 2, end, "run:"));
                }
            } else if (!inline.isEmpty()) {
                found.add(new EmbeddedLanguage(language, i + 1, i + 1, "run: " + shorten(inline)));
            }
        }
        return found;
    }

    /**
     * The {@code shell:} value of the step owning the {@code run:} at {@code runIndex}. Only sibling
     * lines at the same indent are inspected: a deeper line belongs to the run block's own body and
     * a shallower one has left the step.
     */
    private static String stepShell(String[] lines, int runIndex) {
        int runColumn = keyColumn(lines[runIndex]);
        for (int direction : new int[] {-1, 1}) {
            for (int i = runIndex + direction; i >= 0 && i < lines.length; i += direction) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                int column = keyColumn(line);
                if (column < runColumn) {
                    break;
                }
                if (column > runColumn) {
                    // Part of some block body, not a sibling key of this step.
                    continue;
                }
                boolean startsItem = startsListItem(line);
                if (startsItem && direction > 0) {
                    // The next step has begun; its shell: must not leak into this one.
                    break;
                }
                Matcher shell = GITHUB_SHELL_KEY.matcher(line);
                if (shell.find()) {
                    return shell.group(1);
                }
                if (startsItem) {
                    break;
                }
            }
        }
        return null;
    }

    private static String githubShellLanguage(String shell) {
        if (shell == null) {
            return "bash";
        }
        return switch (shell.toLowerCase(Locale.ROOT)) {
            case "pwsh", "powershell" -> "powershell";
            case "cmd" -> "bat";
            case "python" -> "python";
            default -> "bash";
        };
    }

    private static List<EmbeddedLanguage> scanJenkinsSteps(String[] lines) {
        List<EmbeddedLanguage> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = JENKINS_STEP.matcher(lines[i]);
            if (!matcher.find()) {
                continue;
            }
            String language = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "bat" -> "bat";
                case "powershell", "pwsh" -> "powershell";
                default -> "bash";
            };
            String quote = matcher.group(2);
            int end = i + 1;
            if (quote.length() == 3) {
                String rest = lines[i].substring(matcher.end());
                if (!rest.contains(quote)) {
                    for (int j = i + 1; j < lines.length; j++) {
                        end = j + 1;
                        if (lines[j].contains(quote)) {
                            break;
                        }
                    }
                }
            }
            found.add(new EmbeddedLanguage(language, i + 1, end, matcher.group(1)));
        }
        return found;
    }

    // ------------------------------------------------------------------ plain-script scanning

    private static final Pattern HEREDOC = Pattern.compile(
        "(?<![A-Za-z0-9_.\\-/])(perl|python3?|node|ruby|php|awk|gawk|psql|mysql|sqlplus)\\b[^\\n]*?"
            + "<<-?\\s*[\"']?([A-Za-z_][A-Za-z0-9_]*)[\"']?");

    /**
     * Inline foreign programs. {@code sed} is deliberately absent: a {@code sed -e} expression is
     * ubiquitous in shell and flagging it would make almost every script look "mixed".
     */
    private static final List<InlinePattern> INLINE_PATTERNS = List.of(
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])perl\\s+-[A-Za-z]*e\\b"), "perl"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])python3?\\s+-c\\b"), "python"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])node\\s+-e\\b"), "javascript"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])ruby\\s+-e\\b"), "ruby"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])php\\s+-r\\b"), "php"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.\\-/])g?awk\\s+(?:-[^\\s]+\\s+)*['\"]"), "awk"));

    /** A shell program handed to another language's process API, as opposed to one plain command. */
    private static final List<InlinePattern> EMBEDDED_SHELL_PATTERNS = List.of(
        new InlinePattern(Pattern.compile(
            "subprocess\\.(?:run|call|check_call|check_output|Popen)\\s*\\([^)]*shell\\s*=\\s*True"), "bash"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.])os\\.system\\s*\\("), "bash"),
        new InlinePattern(Pattern.compile("(?<![A-Za-z0-9_.])(?:system\\s*\\(|qx[({/\\[]|%x[({\\[])"), "bash"));

    private static final Pattern SHELL_OPERATOR = Pattern.compile("&&|\\|\\||;\\s*\\S|\\S\\s*\\|\\s*\\S");

    private record InlinePattern(Pattern pattern, String language) {
    }

    private static List<EmbeddedLanguage> detectEmbeddedInScript(String dominantLanguage, String[] lines) {
        List<EmbeddedLanguage> found = new ArrayList<>();
        String commentPrefix = ScriptLanguage.fromId(dominantLanguage).commentPrefix();
        boolean hostIsScriptingLanguage = "python".equals(dominantLanguage)
            || "perl".equals(dominantLanguage) || "ruby".equals(dominantLanguage);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isCommentLine(line, commentPrefix)) {
                continue;
            }
            Matcher heredoc = HEREDOC.matcher(line);
            if (heredoc.find()) {
                String language = interpreterLanguage(heredoc.group(1));
                if (language != null && !language.equals(dominantLanguage)) {
                    int end = heredocEndLine(lines, i, heredoc.group(2));
                    found.add(new EmbeddedLanguage(language, i + 2, end, shorten(heredoc.group())));
                    continue;
                }
            }
            EmbeddedLanguage inline = matchInline(line, i, dominantLanguage, INLINE_PATTERNS, false);
            if (inline != null) {
                found.add(inline);
                continue;
            }
            if (hostIsScriptingLanguage) {
                EmbeddedLanguage shell = matchInline(line, i, dominantLanguage, EMBEDDED_SHELL_PATTERNS, true);
                if (shell != null) {
                    found.add(shell);
                }
            }
        }
        return found;
    }

    private static EmbeddedLanguage matchInline(String line, int index, String dominantLanguage,
                                                List<InlinePattern> patterns, boolean requireShellOperator) {
        for (InlinePattern candidate : patterns) {
            Matcher matcher = candidate.pattern().matcher(line);
            if (!matcher.find() || candidate.language().equals(dominantLanguage)) {
                continue;
            }
            if (requireShellOperator && !SHELL_OPERATOR.matcher(line).find()) {
                // One plain external command is a normal call, not an embedded shell program.
                continue;
            }
            return new EmbeddedLanguage(candidate.language(), index + 1, index + 1, shorten(matcher.group()));
        }
        return null;
    }

    private static String interpreterLanguage(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "perl" -> "perl";
            case "python", "python3" -> "python";
            case "node" -> "javascript";
            case "ruby" -> "ruby";
            case "php" -> "php";
            case "awk", "gawk" -> "awk";
            case "psql", "mysql", "sqlplus" -> "sql";
            default -> null;
        };
    }

    /** The 1-based line of the heredoc terminator, or the last line when it is never closed. */
    private static int heredocEndLine(String[] lines, int startIndex, String delimiter) {
        Pattern terminator = Pattern.compile("^\\s*" + Pattern.quote(delimiter) + "\\s*$");
        for (int i = startIndex + 1; i < lines.length; i++) {
            if (terminator.matcher(lines[i]).matches()) {
                return i;
            }
        }
        return lines.length;
    }

    // ------------------------------------------------------------------ small helpers

    private static String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        return content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /**
     * The column the line's YAML key starts at, skipping a leading list dash. Sibling keys of one
     * mapping share this column even when the first of them carries the dash.
     */
    private static int keyColumn(String line) {
        int column = indentOf(line);
        if (column < line.length() && line.charAt(column) == '-') {
            int afterDash = column + 1;
            while (afterDash < line.length()
                && (line.charAt(afterDash) == ' ' || line.charAt(afterDash) == '\t')) {
                afterDash++;
            }
            if (afterDash > column + 1) {
                return afterDash;
            }
        }
        return column;
    }

    private static boolean startsListItem(String line) {
        int indent = indentOf(line);
        return indent < line.length() && line.charAt(indent) == '-';
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        return indent;
    }

    /** {@code |}, {@code |-}, {@code >}, {@code >-} or nothing at all all open an indented block. */
    private static boolean isBlockScalarIndicator(String rest) {
        String value = rest.trim();
        return value.isEmpty() || value.equals("|") || value.equals("|-") || value.equals("|+")
            || value.equals(">") || value.equals(">-") || value.equals(">+");
    }

    /** The 1-based last line of the indented block opened at {@code keyIndex}. */
    private static int blockBodyEndLine(String[] lines, int keyIndex, int keyIndent) {
        int end = keyIndex + 1;
        for (int i = keyIndex + 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            if (indentOf(lines[i]) <= keyIndent) {
                break;
            }
            end = i + 1;
        }
        return end;
    }

    private static boolean isCommentLine(String line, String commentPrefix) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && trimmed.startsWith(commentPrefix);
    }

    private static boolean anyLineMatches(String[] lines, Pattern pattern) {
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** A key written at column zero, i.e. a YAML document's top level. */
    private static boolean hasTopLevelKey(String[] lines, String key) {
        Pattern pattern = Pattern.compile("^" + Pattern.quote(key) + "\\s*:");
        for (String line : lines) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** Keeps a detection trigger short enough for a one-line UI label. */
    private static String shorten(String value) {
        String collapsed = value.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 48 ? collapsed : collapsed.substring(0, 45) + "...";
    }
}
