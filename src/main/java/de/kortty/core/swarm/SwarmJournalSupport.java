package de.kortty.core.swarm;

import de.kortty.core.AutomationJournalRecorder;
import de.kortty.core.AutomationJournalRun;
import de.kortty.core.agent.JournalingAgentCommandRunner;
import de.kortty.model.AutomationRunStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connects an AI Swarm run to its session journals: every agent's runner is wrapped so the
 * agent's commands and output are recorded on that server's journal, and when the run is over
 * each journal gets the agent's outcome, its answer, and the swarm's combined report.
 */
public final class SwarmJournalSupport {

    private SwarmJournalSupport() {
    }

    /** The targets with journaling runners; unchanged when {@code journals} is inactive. */
    public static List<SwarmTarget> wrapTargets(List<SwarmTarget> targets, AutomationJournalRun journals) {
        if (targets == null || journals == null || !journals.isActive()) {
            return targets;
        }
        List<SwarmTarget> wrapped = new ArrayList<>(targets.size());
        for (SwarmTarget target : targets) {
            AutomationJournalRecorder recorder = journals.recorderFor(target.connection());
            wrapped.add(new SwarmTarget(
                target.agentId(),
                target.connection(),
                JournalingAgentCommandRunner.wrap(target.runner(), recorder),
                target.terminalTab(),
                target.sessionId(),
                target.displayName()));
        }
        return wrapped;
    }

    /**
     * Writes each agent's outcome into its server's journal before the run is finished.
     *
     * @param mutationBlockedAgentIds agents stopped because a server change needed approval
     * @param reportTitle             localized title of the combined report note
     */
    public static void recordOutcome(
            AutomationJournalRun journals,
            List<SwarmTarget> targets,
            Collection<SwarmModels.SwarmAgentStatus> statuses,
            Set<String> mutationBlockedAgentIds,
            String aggregatedMarkdown,
            String reportTitle) {
        if (journals == null || !journals.isActive() || targets == null || statuses == null) {
            return;
        }
        Map<String, SwarmTarget> byAgent = new HashMap<>();
        for (SwarmTarget target : targets) {
            byAgent.put(target.agentId(), target);
        }
        for (SwarmModels.SwarmAgentStatus status : statuses) {
            SwarmTarget target = byAgent.get(status.agentId());
            if (target == null) {
                continue;
            }
            AutomationJournalRecorder recorder = journals.recorderFor(target.connection());
            AutomationRunStatus mapped = map(status.state(),
                mutationBlockedAgentIds != null && mutationBlockedAgentIds.contains(status.agentId()));
            recorder.setTargetStatus(mapped);
            String answer = firstNonBlank(status.finalAnswer(), status.errorMessage(), status.currentActivity());
            if (answer != null) {
                recorder.appendNote(de.kortty.ui.I18n.get(
                    "jobscheduler.dialog.status." + mapped.name().toLowerCase(java.util.Locale.ROOT)), answer);
            }
            if (aggregatedMarkdown != null && !aggregatedMarkdown.isBlank()) {
                recorder.appendNote(reportTitle, aggregatedMarkdown);
            }
        }
    }

    /** Swarm agent state → journal outcome. */
    public static AutomationRunStatus map(SwarmModels.SwarmAgentState state, boolean blockedByApproval) {
        if (blockedByApproval) {
            return AutomationRunStatus.BLOCKED;
        }
        if (state == null) {
            return AutomationRunStatus.CANCELLED;
        }
        return switch (state) {
            case DONE -> AutomationRunStatus.SUCCESS;
            case FAILED -> AutomationRunStatus.FAILED;
            default -> AutomationRunStatus.CANCELLED;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
