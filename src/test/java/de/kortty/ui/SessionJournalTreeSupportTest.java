package de.kortty.ui;

import de.kortty.model.AutomationRunStatus;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalTreeSupportTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-30T08:00:00+02:00");

    private static SessionJournalMeta interactive(String dir, OffsetDateTime started) {
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setTitle(dir);
        meta.setStartedAt(started);
        meta.setDirectory(Path.of("/journals", dir));
        return meta;
    }

    private static SessionJournalMeta automation(String dir, SessionJournalSourceKind kind, String sourceId,
                                                 String runId, String host, OffsetDateTime runStart,
                                                 AutomationRunStatus status, long tokens) {
        SessionJournalMeta meta = interactive(dir, runStart.plusSeconds(host.length()));
        meta.setSourceKind(kind);
        meta.setSourceId(sourceId);
        meta.setSourceName("Source " + sourceId);
        meta.setRunId(runId);
        meta.setRunStartedAt(runStart);
        meta.setHost(host);
        meta.setRunStatus(status);
        meta.setAiTotalTokens(tokens);
        meta.setAiCallCount(tokens > 0 ? 1 : 0);
        return meta;
    }

    @Test
    void groupsAutomationJournalsBySourceAndRunAndKeepsInteractiveJournalsFlat() {
        List<SessionJournalMeta> journals = List.of(
            interactive("tab-1", T0.minusHours(5)),
            automation("j1-r1-a", SessionJournalSourceKind.JOB, "job-1", "r1", "web01", T0.minusHours(2),
                AutomationRunStatus.SUCCESS, 100),
            automation("j1-r1-b", SessionJournalSourceKind.JOB, "job-1", "r1", "web02", T0.minusHours(2),
                AutomationRunStatus.FAILED, 50),
            automation("j1-r2-a", SessionJournalSourceKind.JOB, "job-1", "r2", "web01", T0.minusHours(1),
                AutomationRunStatus.SUCCESS, 10),
            automation("s1-r1", SessionJournalSourceKind.SWARM, "chat-1", "r3", "db01", T0,
                AutomationRunStatus.SUCCESS, 5));

        List<SessionJournalTreeSupport.Node> top = SessionJournalTreeSupport.build(journals);

        assertThat(top).hasSize(3);
        // newest first: the swarm group, the job group, then the interactive journal
        assertThat(top.get(0).kind()).isEqualTo(SessionJournalTreeSupport.Kind.GROUP);
        assertThat(top.get(0).sourceKind()).isEqualTo(SessionJournalSourceKind.SWARM);
        SessionJournalTreeSupport.Node job = top.get(1);
        assertThat(job.sourceName()).isEqualTo("Source job-1");
        assertThat(job.runCount()).isEqualTo(2);
        assertThat(job.journalCount()).isEqualTo(3);
        assertThat(job.aiTotalTokens()).isEqualTo(160L);
        assertThat(job.worstStatus()).isEqualTo(AutomationRunStatus.FAILED);
        // runs newest first; the single-journal run collapses into its journal
        assertThat(job.children()).hasSize(2);
        assertThat(job.children().get(0).kind()).isEqualTo(SessionJournalTreeSupport.Kind.JOURNAL);
        SessionJournalTreeSupport.Node run = job.children().get(1);
        assertThat(run.kind()).isEqualTo(SessionJournalTreeSupport.Kind.RUN);
        assertThat(run.children()).hasSize(2);
        assertThat(run.servers()).containsExactly("web01", "web02").inOrder();
        assertThat(run.startedAt()).isEqualTo(T0.minusHours(2));
        assertThat(top.get(2).kind()).isEqualTo(SessionJournalTreeSupport.Kind.JOURNAL);
        assertThat(top.get(2).meta().isAutomation()).isFalse();
    }

    @Test
    void groupTotalsCoverPinsExpiriesAndDuplicates() {
        SessionJournalMeta a = automation("a", SessionJournalSourceKind.JOB, "job-1", "r1", "web01", T0,
            AutomationRunStatus.SUCCESS, 0);
        SessionJournalMeta b = automation("b", SessionJournalSourceKind.JOB, "job-1", "r2", "web01", T0.plusHours(1),
            AutomationRunStatus.SUCCESS, 0);
        a.setPinned(true);
        a.setExpiresAt(T0.plusDays(1));
        b.setExpiresAt(T0.plusDays(5));
        b.setDuplicateRunCount(2);

        SessionJournalTreeSupport.Node group = SessionJournalTreeSupport.build(List.of(a, b)).get(0);

        assertThat(group.anyPinned()).isTrue();
        assertThat(group.allPinned()).isFalse();
        // pinned journals never expire, so the next deletion is the unpinned one's
        assertThat(group.nextExpiry()).isEqualTo(T0.plusDays(5));
        assertThat(group.duplicateRunCount()).isEqualTo(2);
    }

    @Test
    void filtersSelectBySourceOutcomeAndPin() {
        SessionJournalMeta tab = interactive("tab", T0);
        SessionJournalMeta failedJob = automation("j", SessionJournalSourceKind.JOB, "job-1", "r1", "h", T0,
            AutomationRunStatus.FAILED, 0);
        SessionJournalMeta swarm = automation("s", SessionJournalSourceKind.SWARM, "c", "r2", "h", T0,
            AutomationRunStatus.SUCCESS, 0);
        swarm.setPinned(true);

        assertThat(SessionJournalTreeSupport.Filter.INTERACTIVE.matches(tab)).isTrue();
        assertThat(SessionJournalTreeSupport.Filter.INTERACTIVE.matches(failedJob)).isFalse();
        assertThat(SessionJournalTreeSupport.Filter.JOBS.matches(failedJob)).isTrue();
        assertThat(SessionJournalTreeSupport.Filter.SWARM.matches(swarm)).isTrue();
        assertThat(SessionJournalTreeSupport.Filter.FAILED.matches(failedJob)).isTrue();
        assertThat(SessionJournalTreeSupport.Filter.FAILED.matches(swarm)).isFalse();
        assertThat(SessionJournalTreeSupport.Filter.PINNED.matches(swarm)).isTrue();
        assertThat(SessionJournalTreeSupport.Filter.ALL.matches(tab)).isTrue();
    }

    @Test
    void scheduledSwarmJobsAreRecognised() {
        SessionJournalMeta meta = automation("x", SessionJournalSourceKind.JOB, "job-9", "r", "h", T0,
            AutomationRunStatus.SUCCESS, 0);
        meta.setAutomationAction("AI_SWARM");

        SessionJournalTreeSupport.Node group = SessionJournalTreeSupport.build(List.of(meta)).get(0);

        assertThat(SessionJournalTreeSupport.isScheduledSwarm(group)).isTrue();
    }

    @Test
    void badgesDescribeKindStatusAndExpiry() {
        SessionJournalMeta meta = automation("b", SessionJournalSourceKind.JOB, "job-1", "r1", "web01", T0,
            AutomationRunStatus.FAILED, 0);
        meta.setExpiresAt(T0.plusHours(30));
        meta.setDuplicateRunCount(1);
        SessionJournalTreeSupport.Node node = SessionJournalTreeSupport.build(List.of(meta)).get(0);

        List<SessionJournalBadges.Badge> badges = SessionJournalBadges.badges(node, T0, true);

        assertThat(badges.stream().map(SessionJournalBadges.Badge::tone).toList())
            .containsAtLeast(SessionJournalBadges.Tone.INFO, SessionJournalBadges.Tone.DANGER,
                SessionJournalBadges.Tone.WARNING);
        // kind, status, duplicates, "deleted in 1 d" (warning), policy
        assertThat(badges).hasSize(5);
        assertThat(SessionJournalBadges.badges(
            SessionJournalTreeSupport.build(List.of(interactive("t", T0))).get(0), T0, true)).hasSize(1);
    }
}
