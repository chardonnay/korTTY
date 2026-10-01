package de.kortty.ui;

import de.kortty.model.AutomationRunStatus;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the flat list of session journals into the journal manager's tree, without JavaFX:
 * interactive journals stay single rows, while the journals of automation runs are grouped by
 * their source (a JobScheduler job or the AI Swarm) and, below that, by run — one journal per
 * server. A run with a single journal is shown as that journal directly. Every group and run
 * row carries the totals of the journals below it.
 */
public final class SessionJournalTreeSupport {

    public enum Kind { GROUP, RUN, JOURNAL }

    /** The manager's source filter. */
    public enum Filter {
        ALL, INTERACTIVE, JOBS, SWARM, FAILED, PINNED;

        public boolean matches(SessionJournalMeta meta) {
            if (meta == null) {
                return false;
            }
            return switch (this) {
                case ALL -> true;
                case INTERACTIVE -> !meta.isAutomation();
                case JOBS -> meta.getEffectiveSourceKind() == SessionJournalSourceKind.JOB;
                case SWARM -> meta.getEffectiveSourceKind() == SessionJournalSourceKind.SWARM;
                case FAILED -> meta.getRunStatus() != null && meta.getRunStatus().isFailure();
                case PINNED -> meta.isPinned();
            };
        }
    }

    /** One row of the tree. Journal rows carry their meta; group and run rows their totals. */
    public static final class Node {
        private final Kind kind;
        private final String key;
        private final SessionJournalMeta meta;
        private final List<Node> children = new ArrayList<>();
        private SessionJournalSourceKind sourceKind = SessionJournalSourceKind.INTERACTIVE;
        private String sourceName;
        private String automationAction;
        private int runCount;
        private final List<SessionJournalMeta> journals = new ArrayList<>();

        Node(Kind kind, String key, SessionJournalMeta meta) {
            this.kind = kind;
            this.key = key;
            this.meta = meta;
            if (meta != null) {
                journals.add(meta);
                sourceKind = meta.getEffectiveSourceKind();
                sourceName = meta.getSourceName();
                automationAction = meta.getAutomationAction();
            }
        }

        public Kind kind() {
            return kind;
        }

        /** Stable identity across rebuilds (source, run id or journal folder), to keep expansion state. */
        public String key() {
            return key;
        }

        /** The journal of a journal row; null for group and run rows. */
        public SessionJournalMeta meta() {
            return meta;
        }

        public List<Node> children() {
            return children;
        }

        /** Every journal at or below this row. */
        public List<SessionJournalMeta> journals() {
            return journals;
        }

        public SessionJournalSourceKind sourceKind() {
            return sourceKind;
        }

        public String sourceName() {
            return sourceName;
        }

        public String automationAction() {
            return automationAction;
        }

        public int runCount() {
            return runCount;
        }

        public int journalCount() {
            return journals.size();
        }

        public long aiTotalTokens() {
            return journals.stream().mapToLong(SessionJournalMeta::getAiTotalTokens).sum();
        }

        public long aiPromptTokens() {
            return journals.stream().mapToLong(SessionJournalMeta::getAiPromptTokens).sum();
        }

        public long aiCompletionTokens() {
            return journals.stream().mapToLong(SessionJournalMeta::getAiCompletionTokens).sum();
        }

        public int aiCallCount() {
            return journals.stream().mapToInt(SessionJournalMeta::getAiCallCount).sum();
        }

        public double aiCost() {
            return journals.stream().mapToDouble(SessionJournalMeta::getAiCost).sum();
        }

        public String costCurrency() {
            return journals.stream().map(SessionJournalMeta::getAiCostCurrency)
                .filter(Objects::nonNull).findFirst().orElse(null);
        }

        public boolean allLocal() {
            return !journals.isEmpty() && journals.stream().allMatch(SessionJournalMeta::isAiProfileLocal);
        }

        public long storageBytes() {
            return journals.stream().mapToLong(SessionJournalMeta::getStorageBytes).sum();
        }

        public long logEntryCount() {
            return journals.stream().mapToLong(SessionJournalMeta::getLogEntryCount).sum();
        }

        /** Newest start (group), run start (run) or journal start. */
        public OffsetDateTime startedAt() {
            if (kind == Kind.RUN) {
                return journals.stream()
                    .map(m -> m.getRunStartedAt() != null ? m.getRunStartedAt() : m.getStartedAt())
                    .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            }
            return journals.stream().map(SessionJournalMeta::getStartedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        }

        /** The earliest automatic deletion among the unpinned journals below, or null. */
        public OffsetDateTime nextExpiry() {
            return journals.stream().filter(m -> !m.isPinned()).map(SessionJournalMeta::getExpiresAt)
                .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        }

        /** The most severe outcome below: failed over blocked over cancelled over success. */
        public AutomationRunStatus worstStatus() {
            AutomationRunStatus worst = null;
            for (SessionJournalMeta journal : journals) {
                AutomationRunStatus status = journal.getRunStatus();
                if (status != null && (worst == null || severity(status) > severity(worst))) {
                    worst = status;
                }
            }
            return worst;
        }

        public boolean anyPinned() {
            return journals.stream().anyMatch(SessionJournalMeta::isPinned);
        }

        public boolean allPinned() {
            return !journals.isEmpty() && journals.stream().allMatch(SessionJournalMeta::isPinned);
        }

        public boolean anyLive() {
            return journals.stream().anyMatch(SessionJournalMeta::isLive);
        }

        public int duplicateRunCount() {
            return journals.stream().mapToInt(SessionJournalMeta::getDuplicateRunCount).sum();
        }

        /** Distinct servers of the journals below, in order. */
        public List<String> servers() {
            Set<String> servers = new LinkedHashSet<>();
            for (SessionJournalMeta journal : journals) {
                if (journal.getHost() != null) {
                    servers.add(journal.getHost());
                }
            }
            return List.copyOf(servers);
        }

        private static int severity(AutomationRunStatus status) {
            return switch (status) {
                case SUCCESS -> 0;
                case CANCELLED -> 1;
                case BLOCKED -> 2;
                case FAILED -> 3;
            };
        }
    }

    private SessionJournalTreeSupport() {
    }

    /**
     * The top-level rows for {@code journals}: interactive journals and automation groups mixed,
     * newest first. Groups contain their runs newest first; a run with one journal is that journal.
     */
    public static List<Node> build(List<SessionJournalMeta> journals) {
        List<Node> top = new ArrayList<>();
        Map<String, Node> groups = new LinkedHashMap<>();
        Map<String, Map<String, Node>> runsByGroup = new LinkedHashMap<>();
        for (SessionJournalMeta meta : journals) {
            if (meta == null) {
                continue;
            }
            if (!meta.isAutomation()) {
                top.add(journalNode(meta));
                continue;
            }
            String groupKey = "group:" + meta.getEffectiveSourceKind() + ":" + meta.getSourceId();
            Node group = groups.computeIfAbsent(groupKey, key -> {
                Node node = new Node(Kind.GROUP, key, null);
                node.sourceKind = meta.getEffectiveSourceKind();
                top.add(node);
                return node;
            });
            group.journals.add(meta);
            String runKey = "run:" + (meta.getRunId() != null ? meta.getRunId() : String.valueOf(meta.getDirectory()));
            Node run = runsByGroup.computeIfAbsent(groupKey, k -> new LinkedHashMap<>()).computeIfAbsent(runKey, key -> {
                Node node = new Node(Kind.RUN, key, null);
                node.sourceKind = meta.getEffectiveSourceKind();
                return node;
            });
            run.journals.add(meta);
            run.children.add(journalNode(meta));
        }
        for (Map.Entry<String, Node> entry : groups.entrySet()) {
            Node group = entry.getValue();
            List<Node> runs = new ArrayList<>(runsByGroup.getOrDefault(entry.getKey(), Map.of()).values());
            runs.sort(Comparator.comparing(Node::startedAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())));
            for (Node run : runs) {
                run.sourceName = newestSourceName(run.journals);
                run.automationAction = run.journals.get(0).getAutomationAction();
                run.runCount = 1;
                run.children.sort(Comparator.comparing(n -> n.meta().getHost() != null ? n.meta().getHost() : ""));
                group.children.add(run.journals.size() == 1 ? run.children.get(0) : run);
            }
            group.runCount = runs.size();
            group.sourceName = newestSourceName(group.journals);
            group.automationAction = group.journals.stream()
                .max(Comparator.comparing(SessionJournalMeta::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(SessionJournalMeta::getAutomationAction).orElse(null);
        }
        top.sort(Comparator.comparing(Node::startedAt, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())));
        return top;
    }

    /** True for a scheduled AI Swarm job's group, run or journal. */
    public static boolean isScheduledSwarm(Node node) {
        return node != null && node.sourceKind() == SessionJournalSourceKind.JOB
            && "AI_SWARM".equals(node.automationAction());
    }

    private static Node journalNode(SessionJournalMeta meta) {
        return new Node(Kind.JOURNAL, "journal:" + meta.getDirectory(), meta);
    }

    /** The name of the most recent run: a renamed job shows its current name. */
    private static String newestSourceName(List<SessionJournalMeta> journals) {
        return journals.stream()
            .filter(m -> m.getSourceName() != null && !m.getSourceName().isBlank())
            .max(Comparator.comparing(SessionJournalMeta::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(SessionJournalMeta::getSourceName)
            .orElse(null);
    }
}
