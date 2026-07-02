package de.kortty.ui;

import de.kortty.jobscheduler.JobActionType;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.ServerConnection;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Job-Scheduler draft (type {@code AI_SWARM}) from the current state of the swarm window:
 * prompt, profile and SSH targets. Local-shell connections are filtered out — the headless
 * scheduler can only drive SSH sessions, so keeping them would leave the job permanently blocked
 * at the host-key gate.
 */
final class SwarmScheduleDraftSupport {

    private SwarmScheduleDraftSupport() {
    }

    /**
     * @return the draft job, or {@code null} when no schedulable (SSH) target or prompt remains
     */
    static ScheduledJob buildDraft(
        String jobName,
        String prompt,
        String profileId,
        List<ServerConnection> connections,
        boolean readOnly) {

        List<String> connectionIds = sshConnectionIds(connections);
        if (connectionIds.isEmpty() || prompt == null || prompt.isBlank()) {
            return null;
        }
        ScheduledJob job = new ScheduledJob();
        job.setName(jobName != null && !jobName.isBlank() ? jobName.trim() : "AI Swarm");
        job.setEnabled(false);
        job.setTargetConnectionIds(connectionIds);
        job.getAction().setType(JobActionType.AI_SWARM);
        job.getAction().setAiPrompt(prompt.trim());
        job.getAction().setAiProfileId(profileId);
        job.getAction().setSwarmReadOnly(readOnly);
        return job;
    }

    /** Connection ids of all SSH-reachable (non-local-shell) targets, in order. */
    static List<String> sshConnectionIds(List<ServerConnection> connections) {
        List<String> ids = new ArrayList<>();
        if (connections == null) {
            return ids;
        }
        for (ServerConnection connection : connections) {
            if (connection != null && !connection.isLocalShell() && connection.getId() != null) {
                ids.add(connection.getId());
            }
        }
        return ids;
    }

    /** The prompt to prefill: current composer text wins, else the last sent user prompt. */
    static String resolvePromptForDraft(List<SavedSwarmMessage> messages, String composerText) {
        if (composerText != null && !composerText.isBlank()) {
            return composerText.trim();
        }
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                SavedSwarmMessage message = messages.get(i);
                if (message != null && SavedSwarmMessage.ROLE_USER.equals(message.getRole())
                    && message.getContent() != null && !message.getContent().isBlank()) {
                    return message.getContent().trim();
                }
            }
        }
        return null;
    }
}
