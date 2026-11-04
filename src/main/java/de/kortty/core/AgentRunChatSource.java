package de.kortty.core;

import de.kortty.model.TerminalAgentModels;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Everything a finished KI-Agent run in a terminal window hands over to the KI chat: the task, the
 * final answer, every activity in order and where the run is recorded. Built on the FX thread when
 * the run ends, read by {@link AgentRunChatSeeder} off it.
 *
 * <p>{@code journalWrite} is the write of the run's AGENT journal entry, when a journal was active.
 * The handoff waits for it before it links the chat to that entry, so the link cannot overtake the
 * entry. It is {@code null} when no journal was active.</p>
 *
 * @param tokens       total tokens the run reported, or {@code null} when unknown
 * @param journalId    id of the active session journal, or {@code null}
 * @param journalEntryId id of the AGENT entry the run was written as, or {@code null}
 */
public record AgentRunChatSource(
    String runId,
    String terminalSessionId,
    String connectionId,
    String connectionDisplayName,
    String prompt,
    String finalAnswer,
    TerminalAgentModels.Phase finalPhase,
    String profileId,
    String profileName,
    String modelText,
    Instant startedAt,
    Instant finishedAt,
    long elapsedSeconds,
    Long tokens,
    List<TerminalAgentModels.AgentActivity> activities,
    String journalId,
    Path journalDirectory,
    String journalEntryId,
    CompletableFuture<?> journalWrite) {

    public AgentRunChatSource {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }
}
