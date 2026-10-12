package de.kortty.core;

import de.kortty.core.TerminalLogCatalog.Folder;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalLogConfig;
import de.kortty.model.TerminalLogKnownFolder;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogCatalogTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 4);
    private static final String ARCHIVE = "2026-08-04-14-30-12-web01_1.log.gz";
    private static final String PLAIN_SIBLING = "2026-08-04-14-30-12-web01_1.log";

    /** One archive date and retention pair the sweep parity check runs through. */
    private record SweepCase(LocalDate archived, int retention) {
    }

    private Path tempRoot;
    private String savedUserHome;
    private TerminalLogger activeLogger;

    @BeforeMethod
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("kortty-terminal-log-catalog-test");
        // The default folder lives under the home directory, so point it into the temp tree.
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempRoot.resolve("home").toString());
        Files.createDirectories(tempRoot.resolve("home"));
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (activeLogger != null) {
            activeLogger.stop();
            activeLogger = null;
        }
        if (savedUserHome != null) {
            System.setProperty("user.home", savedUserHome);
        }
        if (tempRoot == null || !Files.exists(tempRoot)) {
            return;
        }
        try (var paths = Files.walk(tempRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    // ---- helpers ----

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tempRoot.resolve(name));
    }

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Symbolic links are not available here: " + e.getMessage());
        }
    }

    private static ServerConnection loggingConnection(String name, Path dir, boolean enabled, int retention) {
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        TerminalLogConfig config = connection.getLogConfig();
        config.setEnabled(enabled);
        config.setRetentionDays(retention);
        config.setLogDirectoryPath(dir.toString());
        return connection;
    }

    private static ServerConnection incognitoConnection(String name, Path dir, int retention) {
        ServerConnection connection = loggingConnection(name, dir, true, retention);
        connection.setIncognito(true);
        return connection;
    }

    /** The real path a folder would be keyed by; the normalized one when it does not exist. */
    private static Path keyOf(Path dir) throws IOException {
        return Files.exists(dir) ? dir.toRealPath() : dir.toAbsolutePath().normalize();
    }

    /** The listed folder for {@code dir}, or {@code null} when it is not listed. */
    private static Folder folderAt(List<Folder> folders, Path dir) throws IOException {
        Path key = keyOf(dir);
        return folders.stream().filter(folder -> folder.path().equals(key)).findFirst().orElse(null);
    }

    private static Folder folder(Path dir, Integer retention) {
        return new Folder(dir, dir, retention, false);
    }

    private static void awaitWrites(GlobalSettingsManager settings, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (settings.writeCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(settings.writeCount()).isEqualTo(expected);
    }

    private static Path awaitOpenFile(TerminalLogger logger) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Path file = logger.currentFile();
            if (file != null) {
                return file;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("The logger never opened a file");
    }

    private static Path touch(Path file, FileTime modified) throws IOException {
        Files.writeString(file, "2026-08-04 14:30:12.000 hello\n");
        Files.setLastModifiedTime(file, modified);
        return file;
    }

    // ---- the folder list ----

    @Test
    void theDefaultFolderIsListedEvenBeforeAnythingWasWrittenThere() throws Exception {
        Path defaults = TerminalLogger.defaultDirectory();
        assertThat(defaults.toFile().exists()).isFalse();

        List<Folder> folders = TerminalLogCatalog.folders(List.of(), List.of());

        assertThat(folders).hasSize(1);
        assertThat(folders.get(0).display()).isEqualTo(defaults);
        assertThat(folders.get(0).retentionDays()).isNull();
        assertThat(folders.get(0).manual()).isFalse();
    }

    @Test
    void theDefaultFolderIsKeyedByItsRealPathOnceItExists() throws Exception {
        Path defaults = Files.createDirectories(TerminalLogger.defaultDirectory());

        List<Folder> folders = TerminalLogCatalog.folders(List.of(), List.of());

        assertThat(folders.get(0).path()).isEqualTo(defaults.toRealPath());
    }

    @Test
    void aFolderReachedThroughASymlinkIsListedOnce() throws Exception {
        Path real = dir("real");
        Path alias = tempRoot.resolve("alias");
        symlink(alias, real);

        List<Folder> folders = TerminalLogCatalog.folders(
            List.of(loggingConnection("a", real, true, 30), loggingConnection("b", alias, true, 7)),
            List.of());

        Path realKey = real.toRealPath();
        long listings = folders.stream().filter(folder -> folder.path().equals(realKey)).count();
        assertThat(listings).isEqualTo(1);
        // Both connections share the folder, so the strictest retention applies to all its files.
        assertThat(folderAt(folders, real).retentionDays()).isEqualTo(7);
        // The first name seen is the one the files are reported under.
        assertThat(folderAt(folders, real).display()).isEqualTo(real.toAbsolutePath().normalize());
    }

    @Test
    void theShortestRetentionAcrossConnectionsAndRememberedFoldersWins() throws Exception {
        Path shared = dir("shared");
        Path known = dir("known");
        Path zero = dir("zero");
        Path disabled = dir("disabled");
        Path incognito = dir("incognito");

        List<Folder> folders = TerminalLogCatalog.folders(
            List.of(
                loggingConnection("a", shared, true, 30),
                loggingConnection("b", shared, true, 7),
                loggingConnection("c", known, true, 10),
                loggingConnection("off-on-known", known, false, 1),
                loggingConnection("off", disabled, false, 1),
                incognitoConnection("secret", incognito, 1)),
            List.of(
                new TerminalLogKnownFolder(known.toString(), 3, "2026-08-04T10:00:00Z", false),
                new TerminalLogKnownFolder(zero.toString(), 0, "2026-08-04T10:00:00Z", false)));

        assertThat(folderAt(folders, shared).retentionDays()).isEqualTo(7);
        // The remembered folder's 3 beats the connection's 10; the disabled connection's 1 does not count.
        assertThat(folderAt(folders, known).retentionDays()).isEqualTo(3);
        assertThat(folderAt(folders, zero).retentionDays()).isNull();
    }

    @Test
    void disabledAndIncognitoConnectionsStillListTheirFolderButWithoutRetention() throws Exception {
        Path disabled = dir("disabled");
        Path incognito = dir("incognito");

        List<Folder> folders = TerminalLogCatalog.folders(
            List.of(loggingConnection("off", disabled, false, 1), incognitoConnection("secret", incognito, 1)),
            List.of());

        // Their old files still exist and must be findable, but nothing new is written there, so
        // their retention must not shorten or extend anything.
        assertThat(folderAt(folders, disabled)).isNotNull();
        assertThat(folderAt(folders, disabled).retentionDays()).isNull();
        assertThat(folderAt(folders, incognito)).isNotNull();
        assertThat(folderAt(folders, incognito).retentionDays()).isNull();
    }

    @Test
    void aFolderOnlyAKnownEntryManagesIsManualOnlyWhenTheUserAddedIt() throws Exception {
        Path byHand = dir("by-hand");
        Path both = dir("both");

        List<Folder> folders = TerminalLogCatalog.folders(
            List.of(loggingConnection("x", both, true, 0)),
            List.of(
                new TerminalLogKnownFolder(byHand.toString(), 0, "2026-08-04T10:00:00Z", true),
                new TerminalLogKnownFolder(both.toString(), 0, "2026-08-04T10:00:00Z", true)));

        assertThat(folderAt(folders, byHand).manual()).isTrue();
        // A connection also lists this folder, so it is not merely a manual entry.
        assertThat(folderAt(folders, both).manual()).isFalse();
    }

    @Test
    void foldersThatDoNotExistAreSkippedAndNeverCreated() throws Exception {
        Path missing = tempRoot.resolve("missing");

        List<Folder> folders = TerminalLogCatalog.folders(
            List.of(loggingConnection("gone", missing, true, 30)),
            List.of(new TerminalLogKnownFolder(missing.resolve("nested").toString(), 5, "", true)));

        assertThat(folderAt(folders, missing)).isNull();
        assertThat(folders).hasSize(1); // only the default folder
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void aLegacyFilePathValueListsItsParentFolder() throws Exception {
        Path legacy = dir("legacy");
        Path file = Files.writeString(legacy.resolve("terminal.log"), "old");

        List<Folder> folders = TerminalLogCatalog.folders(List.of(loggingConnection("old", file, true, 30)), List.of());

        assertThat(folderAt(folders, legacy)).isNotNull();
        assertThat(folderAt(folders, legacy).retentionDays()).isEqualTo(30);
        assertThat(folderAt(folders, file)).isNull();
    }

    // ---- the file listing ----

    @Test
    void whenAPlainLogAndItsArchiveCoexistOnlyTheArchiveIsListed() throws Exception {
        Path logs = dir("logs");
        Files.writeString(logs.resolve(PLAIN_SIBLING), "plain");
        Files.writeString(logs.resolve(ARCHIVE), "gz");

        List<TerminalLogFile> files = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, Instant.now());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).name()).isEqualTo(ARCHIVE);
    }

    @Test
    void anArchiveWhosePlainSourceIsStillFreshIsReportedLive() throws Exception {
        // The logger closes the plain file, then gzips it; for that window the archive has no lock
        // and no in-process entry, so the still-recent plain file is what tells the truth.
        Path logs = dir("logs");
        Instant now = Instant.now();
        touch(logs.resolve(PLAIN_SIBLING), FileTime.from(now));
        Files.writeString(logs.resolve(ARCHIVE), "gz");

        TerminalLogFile file = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, now).get(0);

        assertThat(file.live()).isTrue();
    }

    @Test
    void anArchiveStillBeingWrittenIsLiveEvenWhenItsPlainSourceWentQuietLongAgo() throws Exception {
        // An idle session that closes: the plain file is old, but the archive the logger is
        // producing from it keeps changing — that, with the plain sibling still present, is what
        // marks it live. Once the archive itself is old too, the pair is just a copy someone kept.
        Path logs = dir("logs");
        Instant now = Instant.now();
        touch(logs.resolve(PLAIN_SIBLING), FileTime.from(now.minus(Duration.ofMinutes(30))));
        Files.writeString(logs.resolve(ARCHIVE), "gz");

        TerminalLogFile file = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, now).get(0);
        assertThat(file.live()).isTrue();

        touch(logs.resolve(ARCHIVE), FileTime.from(now.minus(Duration.ofMinutes(30))));
        file = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, now).get(0);
        assertThat(file.live()).isFalse();
    }

    @Test
    void aLiveFileUnderASymlinkedAliasIsReportedLive() throws Exception {
        Path real = dir("real");
        Path alias = tempRoot.resolve("alias");
        symlink(alias, real);

        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setRetentionDays(0);
        activeLogger = new TerminalLogger(config, "web01", alias, Clock.systemDefaultZone(),
            new SessionJournalRedactor());
        activeLogger.start();
        activeLogger.log("hello\n");
        activeLogger.awaitQuiet(2000);
        Path open = awaitOpenFile(activeLogger);
        try {
            List<Folder> folders = TerminalLogCatalog.folders(List.of(loggingConnection("web01", alias, true, 0)),
                List.of());
            List<TerminalLogFile> files = TerminalLogCatalog.scan(List.of(folderAt(folders, real)), TODAY,
                Instant.now());

            assertThat(files).hasSize(1);
            TerminalLogFile file = files.get(0);
            assertThat(file.live()).isTrue();
            assertThat(file.directory()).isEqualTo(real.toRealPath());
            assertThat(file.path()).isEqualTo(alias.resolve(open.getFileName()));
        } finally {
            activeLogger.stop();
            activeLogger = null;
        }
    }

    @Test
    void aSymlinkToALogIsNotListed() throws Exception {
        Path logs = dir("logs");
        Path elsewhere = dir("elsewhere");
        Path target = Files.writeString(elsewhere.resolve(ARCHIVE), "gz");
        symlink(logs.resolve("2026-08-04-14-30-12-web02_1.log.gz"), target);

        List<TerminalLogFile> files = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, Instant.now());

        assertThat(files).isEmpty();
    }

    @Test
    void foreignFilesAndDirectoriesAreIgnored() throws Exception {
        Path logs = dir("logs");
        for (String name : List.of("backup.tar.gz", "notes.txt", "important.gz", "2026-08-04-14-30-12-web01.log",
            "2026-08-04-14-30-12-web01_1.LOG", "2026-08-04-14-30-12-web01_1.bin")) {
            Files.writeString(logs.resolve(name), "x");
        }
        Files.writeString(logs.resolve(ARCHIVE), "gz");
        Files.createDirectory(logs.resolve("2026-01-01-10-00-00-web01_1.log.gz.d"));

        List<TerminalLogFile> files = TerminalLogCatalog.scan(List.of(folder(logs, 30)), TODAY, Instant.now());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).name()).isEqualTo(ARCHIVE);
    }

    @Test
    void filesAreSortedNewestFirstThenBySequenceAndPart() throws Exception {
        Path logs = dir("logs");
        for (String name : List.of(
            "2026-08-04-14-30-12-web01_2.log.gz",
            "2026-08-04-14-30-12-web01_1.p2.log.gz",
            "2026-08-04-14-30-12-web01_1.log.gz",
            "2026-08-04-16-00-00-web02_1.log.gz")) {
            Files.writeString(logs.resolve(name), "gz");
        }

        List<TerminalLogFile> files = TerminalLogCatalog.scan(List.of(folder(logs, null)), TODAY, Instant.now());

        assertThat(files).hasSize(4);
        assertThat(files.stream().map(TerminalLogFile::name).toList()).containsExactly(
            "2026-08-04-16-00-00-web02_1.log.gz",
            "2026-08-04-14-30-12-web01_1.log.gz",
            "2026-08-04-14-30-12-web01_1.p2.log.gz",
            "2026-08-04-14-30-12-web01_2.log.gz").inOrder();
    }

    @Test
    void oneUnreadableFolderDoesNotHideTheOthers() throws Exception {
        Path good = dir("good");
        Files.writeString(good.resolve(ARCHIVE), "gz");
        Path notAFolder = Files.writeString(tempRoot.resolve("not-a-folder"), "x");

        List<TerminalLogFile> files = TerminalLogCatalog.scan(
            List.of(folder(tempRoot.resolve("missing"), 30), folder(notAFolder, 30), folder(good, 30)),
            TODAY, Instant.now());

        assertThat(files).hasSize(1);
        assertThat(files.get(0).name()).isEqualTo(ARCHIVE);
    }

    // ---- when a file expires ----

    @Test
    void deletableFromIsTheFirstDayTheSweepWouldDeleteTheFile() {
        assertThat(TerminalLogCatalog.deletableFrom(ARCHIVE, 30)).isEqualTo(LocalDate.of(2026, 9, 4));
        assertThat(TerminalLogCatalog.deletableFrom(ARCHIVE, 1)).isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(TerminalLogCatalog.deletableFrom("2026-02-30-10-00-00-web01_1.log.gz", 1))
            .isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void filesThatNeverExpireHaveNoDeletableDate() {
        assertThat(TerminalLogCatalog.deletableFrom(ARCHIVE, null)).isNull();
        assertThat(TerminalLogCatalog.deletableFrom(ARCHIVE, 0)).isNull();
        assertThat(TerminalLogCatalog.deletableFrom(ARCHIVE, -3)).isNull();
        assertThat(TerminalLogCatalog.deletableFrom(PLAIN_SIBLING, 30)).isNull();
        assertThat(TerminalLogCatalog.deletableFrom("backup.tar.gz", 30)).isNull();
    }

    @Test
    void theListedDeletableDateMatchesWhatTheSweepDoes() throws Exception {
        List<SweepCase> cases = List.of(
            new SweepCase(LocalDate.of(2026, 8, 4), 1),
            new SweepCase(LocalDate.of(2026, 8, 4), 7),
            new SweepCase(LocalDate.of(2026, 1, 31), 30),
            new SweepCase(LocalDate.of(2026, 2, 28), 365),
            new SweepCase(LocalDate.of(2024, 2, 29), 1),
            new SweepCase(LocalDate.of(2026, 3, 1), 2),
            new SweepCase(LocalDate.of(2026, 12, 31), 3650));

        for (int i = 0; i < cases.size(); i++) {
            SweepCase sweepCase = cases.get(i);
            Path dir = dir("parity-" + i);
            String name = TerminalLogNaming.fileName("web01", sweepCase.archived().atTime(14, 30, 12), "log", 1, 1)
                + SessionJournalLogCompressor.GZIP_SUFFIX;
            Path archive = Files.writeString(dir.resolve(name), "gz");

            List<TerminalLogFile> listed = TerminalLogCatalog.scan(
                List.of(folder(dir, sweepCase.retention())), TODAY, Instant.now());
            LocalDate deletable = listed.get(0).deletableFrom();
            String context = sweepCase.archived() + " with retention " + sweepCase.retention();

            assertWithMessage(context).that(deletable)
                .isEqualTo(sweepCase.archived().plusDays(sweepCase.retention() + 1L));
            // The day before the listed date the sweep must keep the file...
            assertWithMessage(context).that(TerminalLogRetention.sweep(dir, sweepCase.retention(),
                deletable.minusDays(1), Set.of())).isEqualTo(0);
            assertWithMessage(context).that(Files.exists(archive)).isTrue();
            // ...and on that date it must delete it.
            assertWithMessage(context).that(TerminalLogRetention.sweep(dir, sweepCase.retention(),
                deletable, Set.of())).isEqualTo(1);
            assertWithMessage(context).that(Files.exists(archive)).isFalse();
        }
    }

    // ---- the remembered folders ----

    @Test
    void rememberingAFolderAddsItOnceAndSavesOnlyWhenWhatIsSavedChanges() throws Exception {
        GlobalSettingsManager settings = new GlobalSettingsManager(tempRoot.resolve("config"));
        Path logs = dir("logs");

        assertThat(TerminalLogCatalog.rememberFolder(settings, logs, 30)).isTrue();

        List<TerminalLogKnownFolder> remembered = settings.getSettings().getTerminalLogKnownFolders();
        assertThat(remembered).hasSize(1);
        assertThat(remembered.get(0).getPath()).isEqualTo(logs.toAbsolutePath().normalize().toString());
        assertThat(remembered.get(0).getRetentionDays()).isEqualTo(30);
        assertThat(remembered.get(0).isManual()).isFalse();
        assertThat(remembered.get(0).getLastUsed()).isNotNull();
        awaitWrites(settings, 1);

        // Same retention again: only the last-used time moves, which is not worth a file write.
        assertThat(TerminalLogCatalog.rememberFolder(settings, logs, 30)).isFalse();
        assertThat(settings.getSettings().getTerminalLogKnownFolders()).hasSize(1);

        assertThat(TerminalLogCatalog.rememberFolder(settings, logs, 7)).isTrue();
        assertThat(settings.getSettings().getTerminalLogKnownFolders().get(0).getRetentionDays()).isEqualTo(7);
        awaitWrites(settings, 2);

        assertThat(TerminalLogCatalog.forgetFolder(settings, logs)).isTrue();
        assertThat(settings.getSettings().getTerminalLogKnownFolders()).isEmpty();
        assertThat(TerminalLogCatalog.forgetFolder(settings, logs)).isFalse();
        awaitWrites(settings, 3);
    }

    @Test
    void theRememberAndForgetCallsTolerateMissingSettingsOrFolder() throws Exception {
        Path logs = dir("logs");
        GlobalSettingsManager settings = new GlobalSettingsManager(tempRoot.resolve("config"));

        assertThat(TerminalLogCatalog.rememberFolder(null, logs, 30)).isFalse();
        assertThat(TerminalLogCatalog.rememberFolder(settings, null, 30)).isFalse();
        assertThat(TerminalLogCatalog.forgetFolder(null, logs)).isFalse();
        assertThat(TerminalLogCatalog.forgetFolder(settings, null)).isFalse();
    }

    @Test
    void upsertRefreshesAnEntryInsteadOfDuplicatingIt() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        Path logs = dir("logs");

        assertThat(TerminalLogCatalog.upsert(settings, logs, 30)).isTrue();
        assertThat(TerminalLogCatalog.upsert(settings, logs, 30)).isFalse();
        // A spelling of the same folder with a dot segment is the same entry.
        assertThat(TerminalLogCatalog.upsert(settings, logs.resolve("..").resolve("logs"), 5)).isTrue();

        assertThat(settings.getTerminalLogKnownFolders()).hasSize(1);
        assertThat(settings.getTerminalLogKnownFolders().get(0).getRetentionDays()).isEqualTo(5);
    }

    @Test
    void upsertKeepsAManualEntryManual() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        Path logs = dir("logs");
        settings.setTerminalLogKnownFolders(List.of(
            new TerminalLogKnownFolder(logs.toAbsolutePath().normalize().toString(), 5, "2026-08-01T00:00:00Z", true)));

        assertThat(TerminalLogCatalog.upsert(settings, logs, 5)).isFalse();

        assertThat(settings.getTerminalLogKnownFolders().get(0).isManual()).isTrue();
    }

    @Test
    void upsertClampsTheRetentionToTheSettingsRange() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        Path logs = dir("logs");

        TerminalLogCatalog.upsert(settings, logs, 99_999);
        assertThat(settings.getTerminalLogKnownFolders().get(0).getRetentionDays())
            .isEqualTo(GlobalSettings.MAX_LOG_RETENTION_DAYS);

        TerminalLogCatalog.upsert(settings, logs, -4);
        assertThat(settings.getTerminalLogKnownFolders().get(0).getRetentionDays()).isEqualTo(0);
    }

    @Test
    void removeDropsOnlyTheMatchingEntry() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        Path logs = dir("logs");
        Path other = dir("other");
        TerminalLogCatalog.upsert(settings, logs, 30);
        TerminalLogCatalog.upsert(settings, other, 30);

        assertThat(TerminalLogCatalog.remove(settings, logs)).isTrue();
        assertThat(TerminalLogCatalog.remove(settings, logs)).isFalse();

        assertThat(settings.getTerminalLogKnownFolders()).hasSize(1);
        assertThat(settings.getTerminalLogKnownFolders().get(0).getPath())
            .isEqualTo(other.toAbsolutePath().normalize().toString());
    }
}
