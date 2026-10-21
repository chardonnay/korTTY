package de.kortty.ui;

import de.kortty.core.AiTokenUsageManager;
import de.kortty.model.TerminalAgentModels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lays out the in-terminal block of KI-Agent design 03: the run's question in a header, one row per step,
 * an open approval with its keys, and after the run a collapsed form with the answer. Pure text layout;
 * {@link AgentTerminalBlockWriter} puts the lines into the terminal and colours them by {@link Role}.
 */
final class AgentInlineBlockLayout {

    /** What a piece of text is, which decides its colour. */
    enum Role {
        BORDER,
        ACCENT,
        TITLE,
        TEXT,
        MUTED,
        OK,
        WARN,
        ERROR,
        THINK,
        KEY
    }

    record Segment(String text, Role role) {
    }

    /** One terminal row of the block. */
    record Line(List<Segment> segments) {

        String plain() {
            StringBuilder text = new StringBuilder();
            for (Segment segment : segments) {
                text.append(segment.text());
            }
            return text.toString();
        }

        int length() {
            return plain().length();
        }
    }

    static final int MIN_WIDTH = 32;
    static final int MAX_WIDTH = 120;
    private static final int MAX_DETAIL_LINES = 6;
    private static final int MAX_APPROVAL_MESSAGE_LINES = 3;

    /** Everything the block shows of one run. FX thread only. */
    static final class BlockState {
        final String prompt;
        final long startedAtMillis;
        final Map<String, TerminalAgentModels.AgentActivity> rows = new LinkedHashMap<>();
        private final Set<String> tokenActivityIds = new HashSet<>();
        // When each running step was first seen, so its elapsed time ticks between updates.
        final Map<String, Long> runningSinceMillis = new java.util.HashMap<>();
        long tokens;
        boolean tokensKnown;
        boolean paused;
        boolean expanded;
        boolean finished;
        long finishedAtMillis = -1L;
        TerminalAgentModels.Phase finalPhase;
        String finalMessage;
        TerminalAgentModels.Approval pendingApproval;
        TerminalAgentModels.PasswordRequest pendingPassword;

        BlockState(String prompt, long startedAtMillis) {
            this.prompt = prompt != null ? prompt.strip() : "";
            this.startedAtMillis = startedAtMillis;
        }

        void accept(TerminalAgentModels.AgentActivity activity, long nowMillis) {
            if (activity == null || activity.id() == null) {
                return;
            }
            rows.put(activity.id(), activity);
            if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                runningSinceMillis.putIfAbsent(activity.id(), nowMillis - Math.max(0L, activity.elapsedSeconds()) * 1000L);
            } else {
                runningSinceMillis.remove(activity.id());
            }
            TerminalAgentModels.AgentActivityTokenUsage usage = activity.tokenUsage();
            if (usage != null && usage.known() && usage.totalTokens() > 0 && tokenActivityIds.add(activity.id())) {
                tokens += usage.totalTokens();
                tokensKnown = true;
            }
        }

        long elapsedMillis(long nowMillis) {
            long end = finished && finishedAtMillis >= 0 ? finishedAtMillis : nowMillis;
            return Math.max(0L, end - startedAtMillis);
        }

        int commandCount() {
            int count = 0;
            for (TerminalAgentModels.AgentActivity activity : rows.values()) {
                if (activity.type() == TerminalAgentModels.AgentActivityType.ACTION) {
                    count++;
                }
            }
            return count;
        }
    }

    private AgentInlineBlockLayout() {
    }

    /** The block's width for a terminal of {@code columns} columns. */
    static int blockWidth(int columns) {
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, columns - 1));
    }

    static List<Line> render(BlockState state, int columns, String spinner, long nowMillis) {
        int width = blockWidth(columns);
        return state.finished ? renderFinished(state, width, nowMillis) : renderRunning(state, width, spinner, nowMillis);
    }

    private static List<Line> renderRunning(BlockState state, int width, String spinner, long nowMillis) {
        List<Line> lines = new ArrayList<>();
        List<Segment> status = new ArrayList<>();
        if (state.paused) {
            status.add(new Segment("⏸ " + I18n.get("aiAgentDesign.inline.paused"), Role.WARN));
        } else {
            status.add(new Segment(spinner, Role.ACCENT));
        }
        status.add(new Segment(" " + meta(state, nowMillis), Role.MUTED));
        lines.add(header(state, width, status));
        boolean promptOpen = state.pendingApproval != null || state.pendingPassword != null;
        for (TerminalAgentModels.AgentActivity activity : state.rows.values()) {
            if (isHidden(state, activity) || (promptOpen && isOpenQuestion(activity))) {
                // An open question is shown with its commands and keys below the steps instead.
                continue;
            }
            lines.add(row(state, activity, width, spinner, nowMillis));
            if (state.expanded) {
                addDetail(lines, activity, width);
            }
        }
        List<Segment> footer = new ArrayList<>();
        if (state.pendingApproval != null) {
            addApproval(lines, state.pendingApproval, width);
            footer.add(new Segment("1 ", Role.KEY));
            footer.add(new Segment(I18n.get("ai.agent.approval.once"), Role.TEXT));
            if (state.pendingApproval.allowAlways()) {
                footer.add(new Segment(" · ", Role.MUTED));
                footer.add(new Segment("2 ", Role.KEY));
                footer.add(new Segment(I18n.get("ai.agent.approval.always"), Role.TEXT));
                footer.add(new Segment(" · ", Role.MUTED));
                footer.add(new Segment("3 ", Role.KEY));
            } else {
                footer.add(new Segment(" · ", Role.MUTED));
                footer.add(new Segment("2 ", Role.KEY));
            }
            footer.add(new Segment(I18n.get("dialog.cancel"), Role.TEXT));
        } else if (state.pendingPassword != null) {
            lines.add(textLine(width, "? ", Role.WARN, firstNonBlank(state.pendingPassword.summary(),
                I18n.get("ai.agent.activity.passwordRequired")), Role.TEXT));
            footer.add(new Segment(I18n.get("aiAgentDesign.inline.passwordHint"), Role.MUTED));
        } else {
            footer.add(new Segment(I18n.get("aiAgentDesign.inline.runningHint"), Role.MUTED));
        }
        lines.add(footer(width, footer));
        return lines;
    }

    private static List<Line> renderFinished(BlockState state, int width, long nowMillis) {
        List<Line> lines = new ArrayList<>();
        List<Segment> status = new ArrayList<>();
        TerminalAgentModels.Phase phase = state.finalPhase;
        if (phase == TerminalAgentModels.Phase.CANCELLED) {
            status.add(new Segment("– " + I18n.get("aiAgentDesign.inline.cancelled"), Role.MUTED));
        } else if (phase == TerminalAgentModels.Phase.FAILED || phase == TerminalAgentModels.Phase.BLOCKED
            || hasFailure(state)) {
            status.add(new Segment("✕ " + I18n.get("aiAgentDesign.inline.failed"), Role.ERROR));
        } else {
            status.add(new Segment("✓", Role.OK));
        }
        status.add(new Segment(" " + I18n.get("aiAgentDesign.inline.steps", state.commandCount())
            + " · " + meta(state, nowMillis), Role.MUTED));
        lines.add(header(state, width, status));
        String answer = finalAnswer(state);
        if (state.expanded) {
            for (TerminalAgentModels.AgentActivity activity : state.rows.values()) {
                if (!isHidden(state, activity) && activity.type() != TerminalAgentModels.AgentActivityType.MESSAGE) {
                    lines.add(row(state, activity, width, "", nowMillis));
                }
            }
        }
        for (String answerLine : wrap(answer, width - 2)) {
            lines.add(new Line(List.of(new Segment("│ ", Role.BORDER), new Segment(answerLine, Role.TEXT))));
        }
        lines.add(new Line(List.of(new Segment("╰─", Role.BORDER))));
        return lines;
    }

    private static Line header(BlockState state, int width, List<Segment> right) {
        List<Segment> left = new ArrayList<>();
        left.add(new Segment("╭─ ", Role.BORDER));
        left.add(new Segment("◆ ", Role.ACCENT));
        left.add(new Segment(I18n.get("aiAgentDesign.inline.title"), Role.TITLE));
        if (!state.prompt.isBlank()) {
            left.add(new Segment(" · ", Role.MUTED));
            left.add(new Segment(oneLine(state.prompt), Role.TEXT));
        }
        return justify(left, right, width, " ", Role.BORDER);
    }

    private static Line footer(int width, List<Segment> content) {
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment("╰─ ", Role.BORDER));
        segments.addAll(content);
        return truncate(segments, width);
    }

    private static Line row(BlockState state, TerminalAgentModels.AgentActivity activity, int width, String spinner, long nowMillis) {
        Segment marker = marker(activity, spinner);
        List<Segment> left = new ArrayList<>();
        left.add(new Segment("│ ", Role.BORDER));
        left.add(marker);
        left.add(new Segment(" ", Role.TEXT));
        Role textRole = activity.type() == TerminalAgentModels.AgentActivityType.MESSAGE ? Role.MUTED : Role.TEXT;
        left.add(new Segment(oneLine(rowText(activity)), textRole));
        String meta = rowMeta(state, activity, nowMillis);
        List<Segment> right = meta.isBlank() ? List.of() : List.of(new Segment(meta, Role.MUTED));
        return justify(left, right, width, " ", Role.TEXT);
    }

    private static void addDetail(List<Line> lines, TerminalAgentModels.AgentActivity activity, int width) {
        if (activity.type() != TerminalAgentModels.AgentActivityType.THINKING
            && activity.type() != TerminalAgentModels.AgentActivityType.ACTION) {
            return;
        }
        String detail = activity.detail();
        if (detail == null || detail.isBlank()) {
            return;
        }
        List<String> wrapped = wrap(detail, width - 4);
        int shown = Math.min(MAX_DETAIL_LINES, wrapped.size());
        for (int i = 0; i < shown; i++) {
            String text = i == shown - 1 && wrapped.size() > shown ? wrapped.get(i) + " …" : wrapped.get(i);
            lines.add(truncate(List.of(new Segment("│   ", Role.BORDER), new Segment(text, Role.MUTED)), width));
        }
    }

    private static void addApproval(List<Line> lines, TerminalAgentModels.Approval approval, int width) {
        String message = firstNonBlank(approval.userMessage(), I18n.get("ai.agent.activity.approvalPrompt"));
        List<String> wrapped = wrap(message, width - 4);
        for (int i = 0; i < Math.min(MAX_APPROVAL_MESSAGE_LINES, wrapped.size()); i++) {
            lines.add(textLine(width, i == 0 ? "? " : "  ", Role.WARN, wrapped.get(i), Role.TEXT));
        }
        if (approval.commands() != null) {
            for (TerminalAgentModels.PlannedCommand command : approval.commands()) {
                if (command != null && command.command() != null) {
                    lines.add(truncate(List.of(
                        new Segment("│   ", Role.BORDER),
                        new Segment("$ ", Role.ACCENT),
                        new Segment(oneLine(command.command()), Role.TITLE)), width));
                }
            }
        }
    }

    private static Line textLine(int width, String marker, Role markerRole, String text, Role textRole) {
        return truncate(List.of(
            new Segment("│ ", Role.BORDER),
            new Segment(marker, markerRole),
            new Segment(oneLine(text), textRole)), width);
    }

    private static Segment marker(TerminalAgentModels.AgentActivity activity, String spinner) {
        if (activity.type() == TerminalAgentModels.AgentActivityType.ERROR
            || activity.status() == TerminalAgentModels.AgentActivityStatus.FAILED) {
            return new Segment("✕", Role.ERROR);
        }
        if (activity.status() == TerminalAgentModels.AgentActivityStatus.CANCELLED) {
            return new Segment("–", Role.MUTED);
        }
        boolean running = activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
        return switch (activity.type()) {
            case ACTION -> running ? new Segment(spinnerOr(spinner, "›"), Role.ACCENT) : new Segment("✓", Role.OK);
            case THINKING -> new Segment(running ? spinnerOr(spinner, "◇") : "◇", Role.THINK);
            case QUESTION -> running ? new Segment("?", Role.WARN) : new Segment("✓", Role.OK);
            case MESSAGE -> new Segment("•", Role.MUTED);
            case ERROR -> new Segment("✕", Role.ERROR);
        };
    }

    private static String spinnerOr(String spinner, String fallback) {
        return spinner != null && !spinner.isBlank() ? spinner : fallback;
    }

    private static String rowText(TerminalAgentModels.AgentActivity activity) {
        return switch (activity.type()) {
            case ACTION -> firstNonBlank(activity.title(), activity.summary());
            case THINKING -> activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING
                ? I18n.get("ai.agent.activity.thinking")
                : firstNonBlank(activity.summary(), activity.title());
            case QUESTION -> firstNonBlank(activity.title(), I18n.get("ai.agent.activity.inputRequired"));
            case ERROR, MESSAGE -> firstNonBlank(activity.summary(), activity.title());
        };
    }

    private static String rowMeta(BlockState state, TerminalAgentModels.AgentActivity activity, long nowMillis) {
        if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING
            && activity.type() != TerminalAgentModels.AgentActivityType.QUESTION) {
            Long since = state.runningSinceMillis.get(activity.id());
            long seconds = since != null && !state.finished
                ? Math.max(0L, (nowMillis - since) / 1000L)
                : Math.max(0L, activity.elapsedSeconds());
            return I18n.get("ai.agent.activity.running") + " " + I18n.get("ai.agent.activity.elapsed", seconds);
        }
        if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
            TerminalAgentModels.AgentActivityTokenUsage usage = activity.tokenUsage();
            return usage != null && usage.known() && usage.totalTokens() > 0
                ? I18n.get("ai.agent.activity.tokens.short", AiTokenUsageManager.formatCompact(usage.totalTokens()))
                : "";
        }
        if (activity.type() == TerminalAgentModels.AgentActivityType.ACTION && activity.summary() != null) {
            return oneLine(activity.summary());
        }
        return "";
    }

    /** The start message repeats the question the header already shows, and debug output stays out. */
    private static boolean isHidden(BlockState state, TerminalAgentModels.AgentActivity activity) {
        if (activity.type() != TerminalAgentModels.AgentActivityType.MESSAGE) {
            return false;
        }
        String text = firstNonBlank(activity.summary(), activity.title());
        return text.isBlank() || oneLine(text).equals(oneLine(state.prompt))
            || (state.finished && !state.expanded);
    }

    private static boolean isOpenQuestion(TerminalAgentModels.AgentActivity activity) {
        return activity.type() == TerminalAgentModels.AgentActivityType.QUESTION
            && activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING;
    }

    private static boolean hasFailure(BlockState state) {
        for (TerminalAgentModels.AgentActivity activity : state.rows.values()) {
            if (activity.type() == TerminalAgentModels.AgentActivityType.ERROR) {
                return true;
            }
        }
        return false;
    }

    /** The answer the collapsed block shows: the final message, else the last completed message row. */
    static String finalAnswer(BlockState state) {
        if (state.finalMessage != null && !state.finalMessage.isBlank()) {
            return state.finalMessage.strip();
        }
        String answer = "";
        for (TerminalAgentModels.AgentActivity activity : state.rows.values()) {
            if (activity.type() == TerminalAgentModels.AgentActivityType.MESSAGE
                || activity.type() == TerminalAgentModels.AgentActivityType.ERROR) {
                String text = firstNonBlank(activity.summary(), activity.title());
                if (!text.isBlank() && !oneLine(text).equals(oneLine(state.prompt))) {
                    answer = text;
                }
            }
        }
        return answer;
    }

    private static String meta(BlockState state, long nowMillis) {
        String time = formatElapsed(state.elapsedMillis(nowMillis));
        if (!state.tokensKnown) {
            return time;
        }
        return time + " · " + I18n.get("ai.agent.activity.tokens.short", AiTokenUsageManager.formatCompact(state.tokens));
    }

    static String formatElapsed(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** {@code left}, padding, then {@code right} at the right edge; the left side is cut to make room. */
    static Line justify(List<Segment> left, List<Segment> right, int width, String pad, Role padRole) {
        int rightLength = 0;
        for (Segment segment : right) {
            rightLength += segment.text().length();
        }
        if (rightLength > width / 2) {
            return truncate(concat(left, List.of(new Segment(" ", Role.TEXT)), right), width);
        }
        Line leftLine = truncate(left, width - rightLength - 1);
        List<Segment> segments = new ArrayList<>(leftLine.segments());
        int gap = width - leftLine.length() - rightLength;
        if (!right.isEmpty() && gap > 0) {
            segments.add(new Segment(pad.repeat(gap), padRole));
            segments.addAll(right);
        } else if (!right.isEmpty()) {
            segments.add(new Segment(" ", Role.TEXT));
            segments.addAll(right);
        }
        return new Line(List.copyOf(segments));
    }

    /** The segments cut to {@code width} characters, the last visible one ending in "…" when cut. */
    static Line truncate(List<Segment> segments, int width) {
        List<Segment> result = new ArrayList<>();
        int used = 0;
        for (Segment segment : segments) {
            String text = segment.text();
            if (used + text.length() <= width) {
                result.add(segment);
                used += text.length();
                continue;
            }
            int room = width - used;
            if (room > 1) {
                result.add(new Segment(text.substring(0, room - 1) + "…", segment.role()));
            } else if (room == 1) {
                result.add(new Segment("…", segment.role()));
            }
            break;
        }
        return new Line(List.copyOf(result));
    }

    /** Word-wraps {@code text} to {@code width} columns, keeping its own line breaks. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        int limit = Math.max(8, width);
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String clean = sanitize(paragraph).stripTrailing();
            if (clean.isEmpty()) {
                lines.add("");
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String word : clean.split(" ")) {
                while (word.length() > limit) {
                    if (current.length() > 0) {
                        lines.add(current.toString());
                        current.setLength(0);
                    }
                    lines.add(word.substring(0, limit));
                    word = word.substring(limit);
                }
                if (current.length() > 0 && current.length() + 1 + word.length() > limit) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(word);
            }
            lines.add(current.toString());
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** One line of text: control characters and line breaks become spaces, runs of spaces collapse. */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        return sanitize(text.replace('\n', ' ').replace('\r', ' ')).replaceAll(" {2,}", " ").strip();
    }

    private static String sanitize(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                clean.append("  ");
            } else if (Character.isISOControl(c)) {
                clean.append(' ');
            } else {
                clean.append(c);
            }
        }
        return clean.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        return second != null ? second.strip() : "";
    }

    private static List<Segment> concat(List<Segment> first, List<Segment> second, List<Segment> third) {
        List<Segment> result = new ArrayList<>(first);
        result.addAll(second);
        result.addAll(third);
        return result;
    }
}
