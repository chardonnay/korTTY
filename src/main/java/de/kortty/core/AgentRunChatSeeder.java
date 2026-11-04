package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SessionJournalChatMessage;
import de.kortty.model.SessionJournalChatSnapshot;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.I18n;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Turns a finished KI-Agent run into a saved AI chat and into the model's view of its steps. Pure: no
 * JavaFX, no I/O, so the handoff rules can be tested directly.
 *
 * <p>Every text is cleaned (ANSI escape sequences and XML-invalid characters removed) and then masked
 * with {@link AiOutboundRedaction#redact}, so a secret that reached a command's output is hidden
 * before it is stored, shown in a journal, or sent to a model.</p>
 */
public final class AgentRunChatSeeder {

    /** Longest command output kept per step in the saved chat (head and tail). */
    public static final int STEP_OUTPUT_CHARS = 8_000;
    /** Longest command kept per step in the saved chat. */
    static final int STEP_COMMAND_CHARS = 2_000;
    /** Longest output of one step in the model's view; the saved chat keeps more. */
    static final int MODEL_STEP_OUTPUT_CHARS = 2_000;
    /** Longest command of one step in the model's view. */
    static final int MODEL_STEP_COMMAND_CHARS = 500;
    /** Default size of the model's view of all steps. */
    public static final int MODEL_BUDGET_CHARS = 24_000;
    /** Longest default title of a chat seeded from a run. */
    public static final int TITLE_CHARS = 80;

    /** CSI, OSC and the two-character escape sequences that a terminal emits around text. */
    private static final Pattern ANSI_SEQUENCE = Pattern.compile(
        "\\x1B\\[[0-?]*[ -/]*[@-~]|\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)|\\x1B[@-Z\\\\^_]");

    private AgentRunChatSeeder() {
    }

    /**
     * The chat for a finished run: the task as the first user message, the final answer as the
     * assistant reply with the run's steps (thinking steps are left out). Its origin fields tie it
     * to the run, so the same run opens the same chat again.
     *
     * @param redactor the terminal session's secret redactor, or {@code null} for the policy rules and token formats only
     * @param title    the chat title, or {@code null} to derive one from the task
     */
    public static SavedAiChat buildChat(AgentRunChatSource source, SessionJournalRedactor redactor, String title) {
        Objects.requireNonNull(source, "source");
        SavedAiChat chat = new SavedAiChat();
        // Masked before the cut: a secret that straddles the title's length limit must not leave a prefix.
        String chatTitle = title != null && !title.isBlank()
            ? text(title, redactor)
            : defaultTitle(mask(clean(source.prompt()), redactor));
        chat.setTitle(chatTitle);
        chat.setCreatedAt(millis(source.startedAt()));
        chat.setUpdatedAt(millis(source.finishedAt()));
        chat.setConnectionDisplayName(text(source.connectionDisplayName(), redactor));
        chat.setActiveAiProfileId(source.profileId());
        chat.setActiveAiProfileName(text(source.profileName(), redactor));
        chat.setOrigin(SavedAiChat.ORIGIN_AGENT_RUN);
        chat.setSourceRunId(source.runId());
        chat.setSourceTerminalSessionId(source.terminalSessionId());
        chat.setSourceConnectionId(source.connectionId());
        chat.setSourceJournalId(source.journalId());
        chat.setSourceJournalEntryId(source.journalEntryId());

        SavedAiChatMessage user = new SavedAiChatMessage();
        user.setRole(SavedAiChatMessage.ROLE_USER);
        user.setContent(text(source.prompt(), redactor));
        user.setCreatedAt(millis(source.startedAt()));

        SavedAiChatMessage assistant = new SavedAiChatMessage();
        assistant.setRole(SavedAiChatMessage.ROLE_ASSISTANT);
        assistant.setContent(answerText(source, redactor));
        assistant.setCreatedAt(millis(source.finishedAt()));
        assistant.setAiProfileId(source.profileId());
        assistant.setAiProfileName(text(source.profileName(), redactor));
        assistant.setAgentSteps(steps(withoutFinalAnswer(source.activities(), source.finalAnswer()), redactor));

        chat.setMessages(List.of(user, assistant));
        return chat;
    }

    /**
     * The journal's copy of a saved chat: its title and messages with the steps, masked again. Taken
     * when the chat changes while the journal records, and once more when the journal stops.
     */
    public static SessionJournalChatSnapshot journalSnapshot(
            SavedAiChat chat, SessionJournalRedactor redactor, OffsetDateTime capturedAt) {
        Objects.requireNonNull(chat, "chat");
        SessionJournalChatSnapshot snapshot = new SessionJournalChatSnapshot();
        snapshot.setCapturedAt(capturedAt);
        snapshot.setTitle(text(chat.getTitle(), redactor));
        List<SessionJournalChatMessage> messages = new ArrayList<>();
        for (SavedAiChatMessage message : chat.getMessages()) {
            SessionJournalChatMessage copy = new SessionJournalChatMessage();
            copy.setRole(message.getRole());
            copy.setContent(text(message.getContent(), redactor));
            copy.setCreatedAt(SessionJournalChatSnapshot.toOffsetDateTime(message.getCreatedAt()));
            copy.setAiProfileName(text(message.getAiProfileName(), redactor));
            copy.setSteps(maskedSteps(message.getAgentSteps(), redactor));
            messages.add(copy);
        }
        snapshot.setMessages(messages);
        return snapshot;
    }

    /**
     * The activities without the run's closing message: the run publishes its final answer as a message activity
     * too, and the chat already shows it as the assistant's answer, so it would appear twice.
     */
    static List<TerminalAgentModels.AgentActivity> withoutFinalAnswer(
            List<TerminalAgentModels.AgentActivity> activities, String finalAnswer) {
        if (activities == null || finalAnswer == null || finalAnswer.isBlank()) {
            return activities;
        }
        List<TerminalAgentModels.AgentActivity> kept = new ArrayList<>();
        for (TerminalAgentModels.AgentActivity activity : activities) {
            if (activity != null && activity.type() == TerminalAgentModels.AgentActivityType.MESSAGE
                && partOf(activity.summary(), finalAnswer) && partOf(activity.detail(), finalAnswer)) {
                continue;
            }
            kept.add(activity);
        }
        return kept;
    }

    /** Whether {@code text} is empty or already said by {@code answer}. */
    private static boolean partOf(String text, String answer) {
        return text == null || text.isBlank() || answer.contains(text.strip());
    }

    /**
     * The steps a run's activities become: every activity in order except thinking. A command step keeps
     * its command, exit code and output (head and tail, marked when cut); the other steps keep title and
     * summary. All texts are cleaned and masked.
     */
    public static List<SavedAgentStep> steps(
            List<TerminalAgentModels.AgentActivity> activities, SessionJournalRedactor redactor) {
        List<SavedAgentStep> steps = new ArrayList<>();
        if (activities == null) {
            return steps;
        }
        for (TerminalAgentModels.AgentActivity activity : activities) {
            if (activity == null || activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
                continue;
            }
            SavedAgentStep step = new SavedAgentStep();
            step.setType(activity.type().name());
            step.setStatus(activity.status() != null ? activity.status().name() : null);
            step.setTitle(text(activity.title(), redactor));
            step.setSummary(text(activity.summary(), redactor));
            step.setElapsedSeconds(activity.elapsedSeconds());
            if (AgentStepReader.isCommand(activity)) {
                step.setCommand(capLine(text(AgentStepReader.command(activity), redactor), STEP_COMMAND_CHARS));
                step.setExitCode(AgentStepReader.exitCode(activity));
                String output = text(String.join("\n", AgentStepReader.output(activity)), redactor);
                step.setTruncated(output.length() > STEP_OUTPUT_CHARS);
                step.setOutput(output.isEmpty() ? null : capLine(output, STEP_OUTPUT_CHARS));
            }
            steps.add(step);
        }
        return steps;
    }

    /** The model's view of the steps within {@link #MODEL_BUDGET_CHARS}. */
    public static String modelSteps(List<SavedAgentStep> steps) {
        return modelSteps(steps, MODEL_BUDGET_CHARS);
    }

    /**
     * The model's view of the steps: one line per step with its status, title and summary, command steps
     * with {@code $ command} and {@code exit N}, and their output head and tail. When the text exceeds the
     * budget, output is dropped from the oldest step first; titles, summaries, commands and exit codes are
     * never dropped, so the model always knows what was tried and how it ended.
     */
    public static String modelSteps(List<SavedAgentStep> steps, int budgetChars) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        List<Rendered> items = new ArrayList<>();
        for (SavedAgentStep step : steps) {
            if (step != null) {
                items.add(new Rendered(step));
            }
        }
        String[] parts = new String[items.size()];
        long budget = budgetChars;
        long total = 0;
        for (int i = 0; i < items.size(); i++) {
            parts[i] = items.get(i).render();
            total += parts[i].length();
        }
        for (int i = 0; i < items.size() && total > budget; i++) {
            Rendered item = items.get(i);
            if (item.output == null) {
                continue;
            }
            item.output = null;
            item.outputOmitted = true;
            String fresh = item.render();
            total += fresh.length() - parts[i].length();
            parts[i] = fresh;
        }
        StringBuilder text = new StringBuilder((int) Math.min(Integer.MAX_VALUE, total));
        for (String part : parts) {
            text.append(part);
        }
        if (text.length() > 0 && text.charAt(text.length() - 1) == '\n') {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    /**
     * A title from the task: whitespace collapsed to one line, at most {@link #TITLE_CHARS} characters
     * with a cut marked by an ellipsis. An empty task gets the generic fallback title.
     */
    public static String defaultTitle(String prompt) {
        String single = clean(prompt != null ? prompt : "").replaceAll("\\s+", " ").strip();
        if (single.isEmpty()) {
            return I18n.get("ai.result.agentRun.title.fallback");
        }
        if (single.length() <= TITLE_CHARS) {
            return single;
        }
        return single.substring(0, TITLE_CHARS - 1).stripTrailing() + "…";
    }

    /** The visible answer: cleaned, sanitised for display, masked; a status line when the run answered nothing. */
    private static String answerText(AgentRunChatSource source, SessionJournalRedactor redactor) {
        String answer = AiResponseSanitizer.sanitizeForDisplay(clean(source.finalAnswer()));
        if (answer.isBlank()) {
            answer = I18n.get(noAnswerKey(source.finalPhase()));
        }
        return mask(answer, redactor);
    }

    private static String noAnswerKey(TerminalAgentModels.Phase phase) {
        if (phase == TerminalAgentModels.Phase.CANCELLED) {
            return "ai.result.agentRun.noAnswer.cancelled";
        }
        if (phase == TerminalAgentModels.Phase.BLOCKED) {
            return "ai.result.agentRun.noAnswer.blocked";
        }
        return "ai.result.agentRun.noAnswer";
    }

    private static List<SavedAgentStep> maskedSteps(List<SavedAgentStep> steps, SessionJournalRedactor redactor) {
        List<SavedAgentStep> copies = new ArrayList<>();
        for (SavedAgentStep step : steps) {
            SavedAgentStep copy = new SavedAgentStep(step);
            copy.setTitle(text(step.getTitle(), redactor));
            copy.setSummary(text(step.getSummary(), redactor));
            copy.setCommand(text(step.getCommand(), redactor));
            copy.setOutput(text(step.getOutput(), redactor));
            copies.add(copy);
        }
        return copies;
    }

    /** One step prepared for the model; the output shrinks to nothing when the budget needs it. */
    private static final class Rendered {

        private final String header;
        private final String command;
        private final String exitLine;
        private String output;
        private boolean outputOmitted;

        Rendered(SavedAgentStep step) {
            String status = step.getStatus() != null && !step.getStatus().isBlank() ? step.getStatus() : step.getType();
            String title = step.getTitle() != null ? step.getTitle() : "";
            String summary = step.getSummary() != null ? step.getSummary().strip() : "";
            this.header = "- [" + (status != null ? status.toLowerCase(Locale.ROOT) : "") + "] "
                + title + (summary.isEmpty() ? "" : ": " + summary);
            this.command = step.getCommand() != null && !step.getCommand().isBlank()
                ? capLine(step.getCommand(), MODEL_STEP_COMMAND_CHARS) : null;
            this.exitLine = step.getExitCode() != null ? "exit " + step.getExitCode() : null;
            this.output = step.getOutput() != null && !step.getOutput().isEmpty()
                ? capLine(step.getOutput(), MODEL_STEP_OUTPUT_CHARS) : null;
        }

        String render() {
            StringBuilder sb = new StringBuilder(header.length() + 64).append(header).append('\n');
            if (command != null) {
                sb.append("  $ ").append(command).append('\n');
            }
            if (exitLine != null) {
                sb.append("  ").append(exitLine).append('\n');
            }
            if (output != null) {
                for (String line : output.split("\n", -1)) {
                    sb.append("  | ").append(line).append('\n');
                }
            } else if (outputOmitted) {
                sb.append("  | [output omitted to fit the budget]\n");
            }
            return sb.toString();
        }
    }

    /** Head and tail of a long text with a marker between them; the same cut as the journal summaries use. */
    private static String capLine(String text, int maxChars) {
        return SessionJournalSummarizer.capLine(text, maxChars);
    }

    /** Cleaned and masked; {@code null} and blank stay as they are. */
    private static String text(String value, SessionJournalRedactor redactor) {
        return mask(clean(value), redactor);
    }

    private static String mask(String value, SessionJournalRedactor redactor) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return AiOutboundRedaction.redact(value, redactor).text();
    }

    /** The text without ANSI escape sequences and without characters an XML file cannot hold. */
    static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return AiChatManager.storable(ANSI_SEQUENCE.matcher(value).replaceAll(""));
    }

    private static long millis(Instant instant) {
        return instant != null ? instant.toEpochMilli() : System.currentTimeMillis();
    }
}
