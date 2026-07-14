package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes AI prompts through a locally installed provider CLI.
 */
public class LocalCliAiService implements AiPromptService, AiSkillUsageTracker {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONNECTION_TEST_SYSTEM_PROMPT = "Reply with exactly OK.";
    private static final String CONNECTION_TEST_USER_PROMPT = "Connection test.";

    private final String providerId;
    private final String executablePath;
    private final String argumentsTemplate;
    private final String model;
    private final AiReasoningEffort reasoningEffort;
    private final AiSkillPromptSupport skillPromptSupport;
    private final Duration requestTimeout;

    public LocalCliAiService(AiProfile profile, AiSkillPromptSupport skillPromptSupport) {
        this(
            profile != null ? profile.getCliProviderId() : null,
            profile != null ? profile.getCliExecutablePath() : null,
            profile != null ? profile.getCliArgumentsTemplate() : null,
            profile != null ? profile.getModel() : null,
            profile != null ? profile.getReasoningEffort() : null,
            skillPromptSupport,
            DEFAULT_TIMEOUT);
    }

    LocalCliAiService(
        String providerId,
        String executablePath,
        String argumentsTemplate,
        String model,
        AiReasoningEffort reasoningEffort,
        AiSkillPromptSupport skillPromptSupport,
        Duration requestTimeout) {

        this.providerId = providerId != null ? providerId.trim() : "";
        this.executablePath = executablePath != null ? executablePath.trim() : "";
        this.argumentsTemplate = argumentsTemplate != null ? argumentsTemplate.trim() : "";
        this.model = model != null ? model.trim() : "";
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_TIMEOUT;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        String systemPrompt = skillPromptSupport.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request);
        return executePromptInternal(systemPrompt, AiPromptBuilder.buildUserPrompt(request), requestTimeout);
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        String effectiveSystemPrompt = skillPromptSupport.appendAgentSkills(systemPrompt, userPrompt);
        return executePromptInternal(effectiveSystemPrompt, userPrompt, requestTimeout);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePrompt(systemPrompt, userPrompt);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executePrompt(systemPrompt, userPrompt);
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    @Override
    public boolean testConnection() {
        try {
            AiExecutionResult result = executePromptInternal(
                CONNECTION_TEST_SYSTEM_PROMPT,
                CONNECTION_TEST_USER_PROMPT,
                TEST_TIMEOUT);
            return result != null && result.content() != null && !result.content().isBlank();
        } catch (Exception e) {
            throw new IllegalStateException("AI CLI connection test failed: " + safeMessage(e), e);
        }
    }

    List<String> buildCommandForTest(Path promptFile, Path systemPromptFile, Path userPromptFile) {
        return buildCommand(promptFile, systemPromptFile, userPromptFile, "").command();
    }

    private AiExecutionResult executePromptInternal(String systemPrompt, String userPrompt, Duration timeout) throws Exception {
        Path tempDir = Files.createTempDirectory("kortty-ai-cli-");
        try {
            String safeSystemPrompt = systemPrompt != null ? systemPrompt : "";
            String safeUserPrompt = userPrompt != null ? userPrompt : "";
            Path promptFile = tempDir.resolve("prompt.txt");
            Path systemPromptFile = tempDir.resolve("system-prompt.txt");
            Path userPromptFile = tempDir.resolve("user-prompt.txt");
            String combinedPrompt = buildCombinedPrompt(safeSystemPrompt, safeUserPrompt);
            Files.writeString(systemPromptFile, safeSystemPrompt, StandardCharsets.UTF_8);
            Files.writeString(userPromptFile, safeUserPrompt, StandardCharsets.UTF_8);
            Files.writeString(promptFile, combinedPrompt, StandardCharsets.UTF_8);
            CliCommand command = buildCommand(promptFile, systemPromptFile, userPromptFile, combinedPrompt);
            CliProcessResult result = AiPowerManagementScope.call(
                () -> runProcess(command.command(), command.stdin(), timeout));
            if (result.exitCode() != 0) {
                throw new IllegalStateException(buildExitMessage(result));
            }
            String stdout = result.stdout();
            return new AiExecutionResult(sanitizeCliOutput(stdout), null, extractThinkReasoning(stdout));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private CliCommand buildCommand(Path promptFile, Path systemPromptFile, Path userPromptFile, String combinedPrompt) {
        String executable = resolveExecutable();
        AiCliArgumentTemplate template = AiCliArgumentTemplate.parse(argumentsTemplate);
        if (!template.containsPromptPlaceholder()) {
            throw new IllegalStateException("AI CLI argument template must include a prompt placeholder.");
        }
        Map<String, String> values = Map.of(
            AiCliArgumentTemplate.MODEL, model,
            AiCliArgumentTemplate.REASONING, reasoningEffort.isApiEnabled() ? reasoningEffort.apiValue() : "",
            AiCliArgumentTemplate.PROMPT_FILE, promptFile.toString(),
            AiCliArgumentTemplate.SYSTEM_PROMPT_FILE, systemPromptFile.toString(),
            AiCliArgumentTemplate.USER_PROMPT_FILE, userPromptFile.toString(),
            AiCliArgumentTemplate.PROMPT, combinedPrompt);
        AiCliArgumentTemplate.ExpandedArguments expanded = template.expandForExecution(values);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(expanded.arguments());
        return new CliCommand(command, expanded.promptOnStdin() ? combinedPrompt : null);
    }

    private String resolveExecutable() {
        if (!executablePath.isBlank()) {
            return executablePath;
        }
        return AiCliProviderRegistry.findProviderExecutable(providerId)
            .orElseThrow(() -> new IllegalStateException("AI CLI executable must be configured."));
    }

    private CliProcessResult runProcess(List<String> command, String stdin, Duration timeout) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IllegalStateException("AI CLI could not be started: " + safeMessage(e), e);
        }
        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));
        writeProcessInput(process, stdin);
        boolean completed;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("AI CLI request timed out after " + timeout.toSeconds() + " seconds.");
        }
        return new CliProcessResult(
            process.exitValue(),
            stdout.get(5, TimeUnit.SECONDS),
            stderr.get(5, TimeUnit.SECONDS));
    }

    private static String readStream(InputStream stream) {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeProcessInput(Process process, String stdin) throws IOException {
        try (OutputStream output = process.getOutputStream()) {
            if (stdin != null) {
                output.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Cleans captured CLI stdout for use as the assistant answer. Some CLIs (notably LM Studio's
     * {@code lms chat} with reasoning models) emit ANSI control sequences, stray carriage returns,
     * and {@code <think>...</think>} reasoning blocks that must not leak into the final text or the
     * JSON the terminal agent parses.
     */
    static String sanitizeCliOutput(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = THINK_BLOCK_PATTERN.matcher(raw).replaceAll("");
        return stripAnsiControlSequences(cleaned).strip();
    }

    // Reasoning blocks emitted by some local models (e.g. gpt-oss via lms);
    // tolerate attributes/whitespace such as <think type="...">.
    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think\\b[^>]*>(.*?)</think\\s*>");
    // ANSI CSI sequences (cursor moves, erase-line, show/hide cursor, colors).
    private static final Pattern ANSI_CSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    // ANSI OSC sequences terminated by BEL or by the String Terminator (ESC backslash).
    private static final Pattern ANSI_OSC_PATTERN =
        Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    /** Strips ANSI CSI/OSC control sequences and stray carriage returns. */
    private static String stripAnsiControlSequences(String text) {
        String cleaned = ANSI_CSI_PATTERN.matcher(text).replaceAll("");
        cleaned = ANSI_OSC_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.replace("\r", "");
    }

    /**
     * Pulls the reasoning out of {@code <think>...</think>} blocks that {@link #sanitizeCliOutput}
     * strips from the answer, so it can be surfaced as {@link AiExecutionResult#reasoning()}.
     * Concatenates multiple blocks; returns {@code null} when there is no reasoning.
     */
    static String extractThinkReasoning(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = THINK_BLOCK_PATTERN.matcher(raw);
        StringBuilder reasoning = new StringBuilder();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block == null) {
                continue;
            }
            block = stripAnsiControlSequences(block).strip();
            if (!block.isBlank()) {
                if (reasoning.length() > 0) {
                    reasoning.append("\n\n");
                }
                reasoning.append(block);
            }
        }
        return reasoning.length() > 0 ? reasoning.toString() : null;
    }

    private static String buildCombinedPrompt(String systemPrompt, String userPrompt) {
        return "System prompt:\n"
            + systemPrompt
            + "\n\nUser prompt:\n"
            + userPrompt
            + "\n";
    }

    private static String buildExitMessage(CliProcessResult result) {
        String stderr = result.stderr() != null ? result.stderr().strip() : "";
        if (stderr.isBlank()) {
            return "AI CLI exited with code " + result.exitCode() + ".";
        }
        return "AI CLI exited with code " + result.exitCode() + ": " + truncate(extractUsefulStderr(stderr), 800);
    }

    private static String extractUsefulStderr(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        List<String> lines = stderr.lines()
            .map(String::strip)
            .filter(line -> !line.isBlank())
            .toList();
        for (String line : lines) {
            if (line.startsWith("ERROR:")) {
                return line;
            }
        }
        for (String line : lines) {
            if (line.contains("\"message\"")) {
                return line;
            }
        }
        return stderr.strip();
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // Temporary prompt files are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Temporary prompt files are best-effort cleanup.
        }
    }

    private record CliProcessResult(int exitCode, String stdout, String stderr) {
    }

    private record CliCommand(List<String> command, String stdin) {
    }
}
