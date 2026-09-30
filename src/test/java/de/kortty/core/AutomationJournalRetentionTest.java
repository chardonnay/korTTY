package de.kortty.core;

import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalConfig;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class AutomationJournalRetentionTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private AutomationJournalConfig sourceConfig;
    private AutomationJournalPolicy policy;
    private final Set<Path> pending = new HashSet<>();

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-automation-journal-retention-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        sourceConfig = new AutomationJournalConfig();
        sourceConfig.setEnabled(true);
        policy = AutomationJournalPolicy.UNRESTRICTED;
        pending.clear();
    }

    @AfterMethod
    void tearDown() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private AutomationJournalRetention retention(OffsetDateTime now) {
        return new AutomationJournalRetention(service, () -> settings, (kind, id) -> sourceConfig,
            () -> policy, pending::contains, Clock.fixed(now.toInstant(), ZoneId.systemDefault()));
    }

    /** A closed automation journal of job "job-1" for run {@code runId}, expiring at {@code expiresAt}. */
    private Path journal(String runId, String host, OffsetDateTime runStart, OffsetDateTime expiresAt,
                         SessionJournalSourceKind kind) throws IOException {
        ServerConnection connection = new ServerConnection("Server " + host, host, 22, "root");
        SessionJournalSession session = service.createSession(connection, "run" + runId + host, settings, List.of(),
            false, new SessionJournalConfig(), meta -> {
                meta.setSourceKind(kind);
                meta.setSourceId("job-1");
                meta.setRunId(runId);
                meta.setRunStartedAt(runStart);
            });
        session.start();
        session.appendOutputChunk("output of " + runId + "\n");
        session.close();
        service.updateAutomationOutcome(session.getDirectory(), AutomationRunStatus.SUCCESS, expiresAt, "hash");
        return session.getDirectory();
    }

    private Path journal(String runId, OffsetDateTime runStart, OffsetDateTime expiresAt) throws IOException {
        return journal(runId, "web01", runStart, expiresAt, SessionJournalSourceKind.JOB);
    }

    private List<Path> remaining() throws IOException {
        return service.listJournals(settings).stream().map(SessionJournalMeta::getDirectory).toList();
    }

    @Test
    void sweepDeletesExpiredJournalsButSkipsPinnedPendingAndInteractive() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Path expired = journal("r1", now.minusDays(20), now.minusDays(6));
        Path fresh = journal("r2", now.minusDays(1), now.plusDays(13));
        Path pinned = journal("r3", now.minusDays(20), now.minusDays(6));
        service.setPinned(pinned, true);
        Path running = journal("r4", now.minusDays(20), now.minusDays(6));
        pending.add(running.toAbsolutePath().normalize());
        Path interactive = service.createSession(new ServerConnection("S", "h", 22, "u"), "tabinteractive01",
            settings, List.of(), false).getDirectory();
        service.getLiveSessions().forEach(SessionJournalSession::close);

        int deleted = retention(now).sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(remaining()).containsExactly(fresh, pinned, running, interactive);
        assertThat(Files.exists(expired)).isFalse();
    }

    @Test
    void userRetentionNeedsDeletePermissionButAnAdminCapDoesNot() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Path userExpiry = journal("r1", now, now.plusDays(1));
        Path noExpiry = journal("r2", now, null);
        policy = new AutomationJournalPolicy(true, true, false, 30, null, null);

        retention(now.plusDays(10)).sweep();
        assertThat(Files.exists(userExpiry)).isTrue();
        assertThat(Files.exists(noExpiry)).isTrue();

        retention(now.plusDays(31)).sweep();
        assertThat(Files.exists(userExpiry)).isFalse();
        assertThat(Files.exists(noExpiry)).isFalse();
    }

    @Test
    void maxRunsDeletesTheOldestRunsAsWholeGroups() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime far = now.plusDays(30);
        Path oldA = journal("r1", "web01", now.minusDays(3), far, SessionJournalSourceKind.JOB);
        Path oldB = journal("r1", "web02", now.minusDays(3), far, SessionJournalSourceKind.JOB);
        Path middle = journal("r2", now.minusDays(2), far);
        Path newest = journal("r3", now.minusDays(1), far);
        sourceConfig.setMaxJournals(2);

        retention(now).sweep();

        assertThat(Files.exists(oldA)).isFalse();
        assertThat(Files.exists(oldB)).isFalse();
        assertThat(remaining()).containsExactly(newest, middle);
    }

    @Test
    void pinnedRunsAreExemptFromTheCountLimit() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime far = now.plusDays(30);
        Path pinnedOld = journal("r1", now.minusDays(3), far);
        service.setPinned(pinnedOld, true);
        Path middle = journal("r2", now.minusDays(2), far);
        Path newest = journal("r3", now.minusDays(1), far);
        sourceConfig.setMaxJournals(1);

        retention(now).sweep();

        assertThat(remaining()).containsExactly(newest, pinnedOld);
        assertThat(Files.exists(middle)).isFalse();
    }

    @Test
    void storageLimitDeletesOldRunsButNeverTheNewest() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime far = now.plusDays(30);
        Path old = journal("r1", now.minusDays(2), far);
        Path newest = journal("r2", now.minusDays(1), far);
        // an admin cap of 1 MB; each journal is only a few KB, so shrink the cap via a huge fake size
        service.updateStorageBytes(old);
        policy = new AutomationJournalPolicy(true, true, true, null, 1, null);
        writeFiller(old, 2 * 1024 * 1024);
        service.updateStorageBytes(old);

        retention(now).sweep();

        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(newest)).isTrue();
    }

    @Test
    void statsSumTokensAndRuns() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Path a = journal("r1", now.minusDays(2), now.plusDays(5));
        Path b = journal("r2", now.minusDays(1), now.plusDays(5));
        service.addAiUsage(a, new AiTokenUsage(100, 20, 120), null);
        service.addAiUsage(b, new AiTokenUsage(10, 5, 15), null);

        AutomationJournalRetention.SourceStats stats = AutomationJournalRetention.statsFor(
            service.listJournals(settings), SessionJournalSourceKind.JOB, "job-1");

        assertThat(stats.runs()).isEqualTo(2);
        assertThat(stats.journals()).isEqualTo(2);
        assertThat(stats.totalTokens()).isEqualTo(135L);
        assertThat(stats.newestRunTokens()).isEqualTo(15L);
        assertThat(AutomationJournalRetention.statsFor(List.of(), SessionJournalSourceKind.JOB, "x"))
            .isSameInstanceAs(AutomationJournalRetention.SourceStats.EMPTY);
    }

    private static void writeFiller(Path dir, int bytes) throws IOException {
        Files.write(dir.resolve("filler.bin"), new byte[bytes]);
    }
}
