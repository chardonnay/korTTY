package de.kortty.jobscheduler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the Mermaid flowchart of the job dependency graph shown by the JobScheduler's
 * dependency diagram. Every user-provided text is emitted as Mermaid entity codes for the
 * characters that could leave a quoted label or trip the renderer's safety checks, so a job name
 * can never inject Mermaid syntax.
 */
public final class JobDependencyMermaidBuilder {

    public enum Direction {
        /** Top to bottom (vertical). */
        TB,
        /** Left to right (horizontal). */
        LR
    }

    public enum Scope {
        /** Every job. */
        ALL,
        /** Only jobs that have at least one dependency edge. */
        CONNECTED,
        /** The focus job plus everything it depends on and everything depending on it. */
        NEIGHBORHOOD
    }

    /** Localized texts of the diagram. */
    public record Labels(
        String gateEdge,
        String chainEdge,
        String neverRan,
        String running,
        String disabled,
        Function<JobRunStatus, String> status) {
    }

    static final String CLASS_SUCCESS = "jobOk";
    static final String CLASS_FAILED = "jobFailed";
    static final String CLASS_BLOCKED = "jobBlocked";
    static final String CLASS_CANCELLED = "jobCancelled";
    static final String CLASS_NEVER = "jobNever";
    static final String CLASS_RUNNING = "jobRunning";
    static final String CLASS_DISABLED = "jobDisabled";
    static final String CLASS_FOCUS = "jobFocus";

    private static final Pattern CLICK_WORD = Pattern.compile("(?i)click");

    private JobDependencyMermaidBuilder() {
    }

    /**
     * @return the Mermaid source, or {@code null} when the scope selects no job
     */
    public static String build(
        JobDependencyGraph graph,
        Direction direction,
        Scope scope,
        String focusJobId,
        Function<String, Optional<JobRunStatus>> lastStatus,
        Predicate<String> running,
        Labels labels) {

        Set<String> included = selectJobs(graph, scope, focusJobId);
        if (included.isEmpty()) {
            return null;
        }
        Map<String, String> nodeIds = new LinkedHashMap<>();
        int index = 0;
        for (String jobId : included) {
            nodeIds.put(jobId, "j" + index++);
        }
        StringBuilder out = new StringBuilder();
        out.append("flowchart ").append(direction == Direction.LR ? "LR" : "TB").append('\n');
        for (String jobId : included) {
            ScheduledJob job = graph.job(jobId).orElseThrow();
            boolean isRunning = running != null && running.test(jobId);
            Optional<JobRunStatus> status = lastStatus != null ? lastStatus.apply(jobId) : Optional.empty();
            StringBuilder label = new StringBuilder(escape(job.getName())).append("<br/>");
            if (isRunning) {
                label.append(escape(labels.running()));
            } else if (status.isPresent()) {
                label.append(escape(labels.status().apply(status.get())));
            } else {
                label.append(escape(labels.neverRan()));
            }
            if (!job.isEnabled()) {
                label.append(" · ").append(escape(labels.disabled()));
            }
            String nodeId = nodeIds.get(jobId);
            out.append("    ").append(nodeId).append("[\"").append(label).append("\"]\n");
        }
        for (String jobId : included) {
            ScheduledJob job = graph.job(jobId).orElseThrow();
            boolean chain = job.getDependencyMode() == JobDependencyMode.CHAIN;
            for (ScheduledJob predecessor : graph.predecessorsOf(jobId)) {
                String from = nodeIds.get(predecessor.getId());
                if (from == null) {
                    continue;
                }
                out.append("    ").append(from)
                    .append(chain ? " -->|\"" : " -.->|\"")
                    .append(escape(chain ? labels.chainEdge() : labels.gateEdge()))
                    .append("\"| ")
                    .append(nodeIds.get(jobId))
                    .append('\n');
            }
        }
        out.append("    classDef ").append(CLASS_SUCCESS).append(" fill:#dcfce7,stroke:#16a34a,color:#14532d,stroke-width:2px\n");
        out.append("    classDef ").append(CLASS_FAILED).append(" fill:#fee2e2,stroke:#dc2626,color:#7f1d1d,stroke-width:2px\n");
        out.append("    classDef ").append(CLASS_BLOCKED).append(" fill:#fef9c3,stroke:#ca8a04,color:#713f12,stroke-width:2px\n");
        out.append("    classDef ").append(CLASS_CANCELLED).append(" fill:#ffedd5,stroke:#ea580c,color:#7c2d12,stroke-width:2px\n");
        out.append("    classDef ").append(CLASS_NEVER).append(" fill:#f1f5f9,stroke:#94a3b8,color:#334155,stroke-width:2px\n");
        out.append("    classDef ").append(CLASS_RUNNING).append(" fill:#dbeafe,stroke:#2563eb,color:#1e3a8a,stroke-width:3px\n");
        out.append("    classDef ").append(CLASS_DISABLED).append(" stroke-dasharray:6 4,opacity:0.7\n");
        out.append("    classDef ").append(CLASS_FOCUS).append(" stroke-width:4px\n");
        for (String jobId : included) {
            ScheduledJob job = graph.job(jobId).orElseThrow();
            String nodeId = nodeIds.get(jobId);
            boolean isRunning = running != null && running.test(jobId);
            String statusClass = isRunning
                ? CLASS_RUNNING
                : statusClass(lastStatus != null ? lastStatus.apply(jobId).orElse(null) : null);
            out.append("    class ").append(nodeId).append(' ').append(statusClass).append('\n');
            if (!job.isEnabled()) {
                out.append("    class ").append(nodeId).append(' ').append(CLASS_DISABLED).append('\n');
            }
            if (scope == Scope.NEIGHBORHOOD && jobId.equals(focusJobId)) {
                out.append("    class ").append(nodeId).append(' ').append(CLASS_FOCUS).append('\n');
            }
        }
        return out.toString();
    }

    static Set<String> selectJobs(JobDependencyGraph graph, Scope scope, String focusJobId) {
        Set<String> included = new LinkedHashSet<>();
        if (graph == null) {
            return included;
        }
        Scope effective = scope != null ? scope : Scope.ALL;
        if (effective == Scope.NEIGHBORHOOD && graph.job(focusJobId).isEmpty()) {
            effective = Scope.CONNECTED;
        }
        if (effective == Scope.NEIGHBORHOOD) {
            Set<String> wanted = new LinkedHashSet<>(graph.ancestorsOf(focusJobId));
            wanted.add(focusJobId);
            wanted.addAll(graph.descendantsOf(focusJobId));
            // Keep the snapshot order so node ids are stable between renders.
            for (ScheduledJob job : graph.jobs()) {
                if (wanted.contains(job.getId())) {
                    included.add(job.getId());
                }
            }
            return included;
        }
        for (ScheduledJob job : graph.jobs()) {
            if (effective == Scope.ALL || graph.isConnected(job.getId())) {
                included.add(job.getId());
            }
        }
        return included;
    }

    static String statusClass(JobRunStatus status) {
        if (status == null) {
            return CLASS_NEVER;
        }
        return switch (status) {
            case SUCCESS -> CLASS_SUCCESS;
            case FAILED -> CLASS_FAILED;
            case BLOCKED -> CLASS_BLOCKED;
            case CANCELLED -> CLASS_CANCELLED;
            case RUNNING -> CLASS_RUNNING;
        };
    }

    /**
     * Encodes the characters that are Mermaid syntax inside a quoted label, or that the renderer's
     * source checks treat as links, directives, icons or click handlers, as {@code #<code>;}.
     */
    static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                out.append(' ');
            } else if (Character.isISOControl(codePoint)) {
                // dropped
            } else if ("\"#:;=()[]{}<>|`&%@\\-.~*".indexOf(codePoint) >= 0) {
                out.append('#').append(codePoint).append(';');
            } else {
                out.appendCodePoint(codePoint);
            }
        });
        Matcher click = CLICK_WORD.matcher(out);
        StringBuilder safe = new StringBuilder(out.length() + 8);
        while (click.find()) {
            String word = click.group();
            click.appendReplacement(safe, Matcher.quoteReplacement(
                "#" + (int) word.charAt(0) + ";" + word.substring(1)));
        }
        click.appendTail(safe);
        return safe.toString();
    }
}
