package de.kortty.core;

import com.github.luben.zstd.ZstdInputStream;
import de.kortty.core.TerminalLogExporter.Progress;
import de.kortty.core.TerminalLogExporter.Result;
import de.kortty.core.TerminalLogExporter.Status;
import de.kortty.core.TerminalLogNaming.Compression;
import de.kortty.model.TerminalLogConfig;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogExporterTest {

    private static final int MIB = 1024 * 1024;
    private static final String BASE_NAME = "2026-08-04-14-30-12-web01_1.log";
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 4, 14, 30, 12);

    private Path tempDir;
    private Path outDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-export-test");
        outDir = tempDir.resolve("exported");
    }

    @AfterMethod
    void tearDown() throws IOException {
        deleteTree(tempDir);
    }

    /**
     * Removes the tree. Directories are made writable first, because a test may have locked one to
     * provoke a failure and a locked directory cannot have its contents removed.
     */
    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> paths = Files.walk(root)) {
            all = paths.toList();
        }
        for (Path path : all) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // Not POSIX, or not ours to change: the delete below reports anything that matters.
                }
            }
        }
        for (Path path : all.stream().sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(path);
        }
    }

    /** A PLAIN_TEXT log with {@code count} entries, {@code stepMillis} apart, starting at {@code first}. */
    private static byte[] plainLog(LocalDateTime first, long stepMillis, int count) {
        StringBuilder text = new StringBuilder();
        LocalDateTime at = first;
        for (int i = 0; i < count; i++) {
            appendEntry(text, at, i);
            at = at.plusNanos(stepMillis * 1_000_000L);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A PLAIN_TEXT log of at least {@code minBytes}, one entry every 7 ms from {@link #START}. */
    private static byte[] sizedPlainLog(long minBytes) {
        StringBuilder text = new StringBuilder();
        LocalDateTime at = START;
        for (int i = 0; (long) text.length() < minBytes; i++) {
            appendEntry(text, at, i);
            at = at.plusNanos(7_000_000L);
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendEntry(StringBuilder text, LocalDateTime at, int i) {
        text.append('[').append(TerminalLogger.TIMESTAMP_FORMAT.format(at)).append("] row ").append(i)
            .append(' ').append(Integer.toHexString(i * 0x9E3779B1))
            .append(" the quick brown fox jumps over the lazy dog ").append(i % 97).append('\n');
    }

    /**
     * Writes a source log in the given compression, back-dated: a file written moments ago reads as
     * live — an uncompressed one by the grace period, an archive beside its plain sibling as "still
     * being compressed" — which is the liveness rule working, not what these tests want.
     */
    private Path writeSource(String baseName, byte[] content, Compression compression) throws IOException {
        Path file = tempDir.resolve(baseName + compression.suffix);
        try (OutputStream out = TerminalLogCodecs.openOutput(file, compression)) {
            out.write(content);
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
        return file;
    }

    private static TerminalLogFile entryFor(Path file) throws IOException {
        TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse(file.getFileName().toString());
        assertWithMessage("owned name for %s", file.getFileName()).that(parsed).isNotNull();
        return new TerminalLogFile(file, file.getParent(), parsed, Files.size(file),
            Files.getLastModifiedTime(file), null, false);
    }

    private static byte[] decode(Path file) throws IOException {
        try (InputStream in = TerminalLogCodecs.openInput(file)) {
            return in.readAllBytes();
        }
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static TerminalLogConfig plainConfig() {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setFormat(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        config.setCompress(false);
        config.setRotateDaily(true);
        config.setRetentionDays(0);
        return config;
    }

    /** Waits for a logger in this process to open its file, so the file is genuinely live. */
    private static Path awaitLiveFile(Path dir) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Stream<Path> files = Files.list(dir)) {
                Optional<Path> live = files.filter(Files::isRegularFile).filter(TerminalLogger::isLive).findFirst();
                if (live.isPresent()) {
                    return live.get();
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("The logger never opened a file");
    }

    @Test
    void everySourceCompressionExportsToEveryTargetWithTheSameContent() throws Exception {
        byte[] plain = sizedPlainLog(8 * MIB);
        for (Compression sourceCompression : Compression.values()) {
            Path source = writeSource(BASE_NAME, plain, sourceCompression);
            byte[] sourceBefore = Files.readAllBytes(source);
            TerminalLogFile file = entryFor(source);
            Path dir = outDir.resolve("from-" + sourceCompression.name().toLowerCase(Locale.ROOT));
            for (Compression target : Compression.values()) {
                String label = sourceCompression + " to " + target;
                Path targetFile = dir.resolve(TerminalLogExporter.targetName(source.getFileName().toString(), target, null));

                Result result = TerminalLogExporter.exportOne(file, targetFile, target, false, null,
                    List.of(), bytes -> { }, () -> false);

                assertWithMessage(label).that(result.status()).isEqualTo(Status.OK);
                assertWithMessage(label).that(Arrays.equals(decode(targetFile), plain)).isTrue();
                assertWithMessage(label).that(result.bytesWritten()).isEqualTo(Files.size(targetFile));
                assertWithMessage(label + " source").that(Arrays.equals(Files.readAllBytes(source), sourceBefore))
                    .isTrue();
            }
        }
    }

    @Test
    void targetNamesSwapTheCompressionAndKeepThePart() {
        assertThat(TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.p2.log.gz", Compression.ZSTD, null))
            .isEqualTo("2026-08-04-14-30-12-web01_1.p2.log.zst");
        assertThat(TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.log.zst", Compression.NONE, null))
            .isEqualTo("2026-08-04-14-30-12-web01_1.log");
        assertThat(TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.log", Compression.GZIP, null))
            .isEqualTo("2026-08-04-14-30-12-web01_1.log.gz");
        // An open range is no range at all: the name carries no cut tag.
        assertThat(TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.p2.log.bz2", Compression.BZIP2,
            LogTimeRange.of(null, null, null, null)))
            .isEqualTo("2026-08-04-14-30-12-web01_1.p2.log.bz2");
    }

    @Test
    void rangeNamesCarryTheCutTagAndNeverParseAsKorttyLogs() {
        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(15, 0),
            LocalDate.of(2026, 8, 4), LocalTime.of(16, 30));

        String zst = TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.p2.log.gz", Compression.ZSTD, range);
        assertThat(zst).isEqualTo("2026-08-04-14-30-12-web01_1.p2-cut-20260804-1500-20260804-1631.log.zst");
        assertThat(TerminalLogNaming.parse(zst)).isNull();

        String gz = TerminalLogExporter.targetName("2026-08-04-14-30-12-web01_1.p2.log.gz", Compression.GZIP, range);
        assertThat(TerminalLogNaming.parse(gz)).isNull();
        assertThat(TerminalLogNaming.isOwnedArchive(gz)).isFalse();
        assertThat(TerminalLogNaming.archiveDate(gz)).isNull();
    }

    @Test
    void onlyAnOwnedArchiveInsideAListedFolderIsSweepable() throws IOException {
        Path folder = Files.createDirectories(tempDir.resolve("logs")).toRealPath();
        Path archive = folder.resolve("2026-08-04-14-30-12-web01_1.log.gz");

        assertThat(TerminalLogExporter.wouldBeSwept(archive, List.of(folder))).isTrue();
        assertThat(TerminalLogExporter.wouldBeSwept(archive, List.of(outDir))).isFalse();
        assertThat(TerminalLogExporter.wouldBeSwept(archive, List.of())).isFalse();
        assertThat(TerminalLogExporter.wouldBeSwept(folder.resolve("2026-08-04-14-30-12-web01_1.log"), List.of(folder)))
            .isFalse();
        assertThat(TerminalLogExporter.wouldBeSwept(folder.resolve("2026-08-04-14-30-12-web01_1.log.zst"), List.of(folder)))
            .isFalse();
        assertThat(TerminalLogExporter.wouldBeSwept(
            folder.resolve("2026-08-04-14-30-12-web01_1-cut-20260804-1500-20260804-1631.log.gz"), List.of(folder)))
            .isFalse();
    }

    @Test
    void aWholeCopyTheSweepWouldTakeIsRefusedButARangeCopyIsKeptAcrossASweep() throws Exception {
        Path folder = tempDir.toRealPath();
        Path source = writeSource(BASE_NAME, plainLog(START, 7, 300), Compression.NONE);
        TerminalLogFile file = entryFor(source);

        Path wholeCopy = folder.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, null));
        Result refused = TerminalLogExporter.exportOne(file, wholeCopy, Compression.GZIP, false, null,
            List.of(folder), bytes -> { }, () -> false);
        assertThat(refused.status()).isEqualTo(Status.WOULD_BE_SWEPT);
        assertThat(Files.exists(wholeCopy)).isFalse();

        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(14, 30),
            LocalDate.of(2026, 8, 4), LocalTime.of(14, 30));
        Path cut = folder.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, range));
        Result kept = TerminalLogExporter.exportOne(file, cut, Compression.GZIP, false, range,
            List.of(folder), bytes -> { }, () -> false);
        assertThat(kept.status()).isEqualTo(Status.OK);
        assertThat(kept.entriesKept()).isEqualTo(300);

        Path expired = writeSource("2026-08-04-14-30-12-web01_2.log", plainLog(START, 7, 10), Compression.GZIP);
        int swept = TerminalLogRetention.sweep(folder, 1, LocalDate.now().plusYears(1), Set.of());
        assertThat(swept).isEqualTo(1);
        assertThat(Files.exists(expired)).isFalse();
        assertThat(Files.exists(cut)).isTrue();
    }

    @Test
    void anExistingTargetIsLeftAloneUnlessOverwritingIsAsked() throws Exception {
        Path source = writeSource(BASE_NAME, plainLog(START, 7, 50), Compression.NONE);
        TerminalLogFile file = entryFor(source);
        Files.createDirectories(outDir);
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, null));
        Files.writeString(target, "previous export");

        Result refused = TerminalLogExporter.exportOne(file, target, Compression.GZIP, false, null,
            List.of(), bytes -> { }, () -> false);
        assertThat(refused.status()).isEqualTo(Status.EXISTS);
        assertThat(Files.readString(target)).isEqualTo("previous export");
        assertThat(names(outDir)).containsExactly(target.getFileName().toString());

        Result replaced = TerminalLogExporter.exportOne(file, target, Compression.GZIP, true, null,
            List.of(), bytes -> { }, () -> false);
        assertThat(replaced.status()).isEqualTo(Status.OK);
        assertThat(Arrays.equals(decode(target), Files.readAllBytes(source))).isTrue();
        assertThat(names(outDir)).containsExactly(target.getFileName().toString());
    }

    @Test
    void exportingOntoTheSourceItselfIsRefusedEvenWhenOverwritingIsAsked() throws Exception {
        Path source = writeSource(BASE_NAME, plainLog(START, 7, 50), Compression.GZIP);
        byte[] before = Files.readAllBytes(source);

        Result result = TerminalLogExporter.exportOne(entryFor(source), source, Compression.GZIP, true, null,
            List.of(), bytes -> { }, () -> false);

        assertThat(result.status()).isEqualTo(Status.SAME_FILE);
        assertThat(Arrays.equals(Files.readAllBytes(source), before)).isTrue();
    }

    @Test
    void aFileALoggerIsStillWritingIsReportedLiveAndNotCopied() throws Exception {
        TerminalLogger logger = new TerminalLogger(plainConfig(), "web01", tempDir, Clock.systemDefaultZone(),
            new SessionJournalRedactor());
        logger.start();
        try {
            logger.log("still writing\n");
            Path live = awaitLiveFile(tempDir);
            Path target = outDir.resolve(TerminalLogExporter.targetName(live.getFileName().toString(),
                Compression.GZIP, null));

            Result result = TerminalLogExporter.exportOne(entryFor(live), target, Compression.GZIP, false, null,
                List.of(), bytes -> { }, () -> false);

            assertThat(result.status()).isEqualTo(Status.LIVE);
            assertThat(Files.exists(outDir)).isFalse();
        } finally {
            logger.stop();
        }
    }

    @Test
    void cancellationOfAWholeCopyLeavesNeitherTempFileNorTarget() throws Exception {
        Path source = writeSource(BASE_NAME, sizedPlainLog(8 * MIB), Compression.GZIP);
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.ZSTD, null));
        AtomicInteger checks = new AtomicInteger();

        // Let the first block through, then stop.
        Result result = TerminalLogExporter.exportOne(entryFor(source), target, Compression.ZSTD, false, null,
            List.of(), bytes -> { }, () -> checks.incrementAndGet() > 1);

        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        assertThat(Files.exists(target)).isFalse();
        assertThat(names(outDir)).isEmpty();
    }

    @Test
    void cancellationOfARangeExportLeavesNeitherTempFileNorTarget() throws Exception {
        Path source = writeSource(BASE_NAME, sizedPlainLog(8 * MIB), Compression.NONE);
        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(14, 30),
            LocalDate.of(2026, 8, 4), LocalTime.of(15, 0));
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, range));
        AtomicInteger checks = new AtomicInteger();

        Result result = TerminalLogExporter.exportOne(entryFor(source), target, Compression.GZIP, false, range,
            List.of(), bytes -> { }, () -> checks.incrementAndGet() > 1);

        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        assertThat(Files.exists(target)).isFalse();
        assertThat(names(outDir)).isEmpty();
    }

    @Test
    void progressIsMonotonicAndEndsAtTheSourceSize() throws Exception {
        Path source = writeSource(BASE_NAME, sizedPlainLog(8 * MIB), Compression.NONE);
        List<Progress> seen = new ArrayList<>();

        List<Result> results = TerminalLogExporter.exportAll(List.of(entryFor(source)), outDir, Compression.GZIP,
            false, null, List.of(), seen::add, () -> false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(Status.OK);
        assertThat(seen.size()).isGreaterThan(2);
        for (int i = 1; i < seen.size(); i++) {
            assertWithMessage("progress update %s", i).that(seen.get(i).bytesDone())
                .isAtLeast(seen.get(i - 1).bytesDone());
        }
        Progress last = seen.get(seen.size() - 1);
        assertThat(last.bytesDone()).isEqualTo(Files.size(source));
        assertThat(last.bytesTotal()).isEqualTo(Files.size(source));
        assertThat(last.filesDone()).isEqualTo(1);
        assertThat(last.filesTotal()).isEqualTo(1);
        assertThat(last.currentName()).isEqualTo(BASE_NAME);
    }

    @Test
    void zstdOutputIsReadableByTheZstdLibrary() throws Exception {
        byte[] plain = plainLog(START, 7, 4000);
        Path source = writeSource(BASE_NAME, plain, Compression.NONE);
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.ZSTD, null));

        Result result = TerminalLogExporter.exportOne(entryFor(source), target, Compression.ZSTD, false, null,
            List.of(), bytes -> { }, () -> false);

        assertThat(result.status()).isEqualTo(Status.OK);
        try (InputStream in = new ZstdInputStream(Files.newInputStream(target))) {
            assertWithMessage("zstd content").that(Arrays.equals(in.readAllBytes(), plain)).isTrue();
        }
    }

    @Test
    void exportedFilesAreOwnerOnlyWhereThePosixPermissionsExist() throws Exception {
        if (Files.getFileAttributeView(tempDir, PosixFileAttributeView.class) == null) {
            throw new SkipException("No POSIX permissions on this file system");
        }
        Path source = writeSource(BASE_NAME, plainLog(START, 7, 100), Compression.NONE);
        TerminalLogFile file = entryFor(source);
        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(14, 30),
            LocalDate.of(2026, 8, 4), LocalTime.of(14, 30));
        Path whole = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, null));
        Path cut = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, range));

        assertThat(TerminalLogExporter.exportOne(file, whole, Compression.GZIP, false, null,
            List.of(), bytes -> { }, () -> false).status()).isEqualTo(Status.OK);
        assertThat(TerminalLogExporter.exportOne(file, cut, Compression.GZIP, false, range,
            List.of(), bytes -> { }, () -> false).status()).isEqualTo(Status.OK);

        Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(whole)).containsExactlyElementsIn(ownerOnly);
        assertThat(Files.getPosixFilePermissions(cut)).containsExactlyElementsIn(ownerOnly);
    }

    @Test
    void aRangeExportKeepsOnlyTheEntriesInsideIt() throws Exception {
        // One entry a minute from 14:20 to 14:50; 14:30 through 14:45 is sixteen of them.
        Path source = writeSource(BASE_NAME, plainLog(LocalDateTime.of(2026, 8, 4, 14, 20, 0), 60_000L, 31),
            Compression.NONE);
        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(14, 30),
            LocalDate.of(2026, 8, 4), LocalTime.of(14, 45));
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, range));

        Result result = TerminalLogExporter.exportOne(entryFor(source), target, Compression.GZIP, false, range,
            List.of(), bytes -> { }, () -> false);

        assertThat(result.status()).isEqualTo(Status.OK);
        assertThat(result.entriesKept()).isEqualTo(16);
        String[] lines = new String(decode(target), StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasLength(16);
        assertThat(lines[0]).startsWith("[2026-08-04 14:30:00.000] ");
        assertThat(lines[15]).startsWith("[2026-08-04 14:45:00.000] ");
    }

    @Test
    void aRangeWithNoEntriesWritesNothingAtAll() throws Exception {
        Path source = writeSource(BASE_NAME, plainLog(START, 7, 50), Compression.NONE);
        LogTimeRange nextDay = LogTimeRange.of(LocalDate.of(2026, 8, 5), null, null, null);
        Path target = outDir.resolve(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, nextDay));

        Result result = TerminalLogExporter.exportOne(entryFor(source), target, Compression.GZIP, false, nextDay,
            List.of(), bytes -> { }, () -> false);

        assertThat(result.status()).isEqualTo(Status.EMPTY_RANGE);
        assertThat(Files.exists(target)).isFalse();
        assertThat(names(outDir)).isEmpty();
    }

    @Test
    void exportAllSkipsFilesOutsideTheRangeWithoutOpeningThem() throws Exception {
        // The first file is not on disk at all. A file the range rules out must never be opened, so it
        // comes back EMPTY_RANGE; opening it would have failed.
        Path ghost = tempDir.resolve("2026-08-01-09-00-00-web02_1.log");
        TerminalLogFile outside = new TerminalLogFile(ghost, tempDir,
            TerminalLogNaming.parse(ghost.getFileName().toString()), 1024L,
            FileTime.from(LocalDateTime.of(2026, 8, 1, 10, 0).atZone(ZoneId.systemDefault()).toInstant()),
            null, false);

        Path inside = writeSource(BASE_NAME, plainLog(LocalDateTime.of(2026, 8, 4, 14, 20, 0), 60_000L, 31),
            Compression.NONE);
        Files.setLastModifiedTime(inside,
            FileTime.from(LocalDateTime.of(2026, 8, 4, 14, 50, 0).atZone(ZoneId.systemDefault()).toInstant()));
        LogTimeRange range = LogTimeRange.of(LocalDate.of(2026, 8, 4), LocalTime.of(14, 30),
            LocalDate.of(2026, 8, 4), LocalTime.of(14, 45));

        List<Result> results = TerminalLogExporter.exportAll(List.of(outside, entryFor(inside)), outDir,
            Compression.GZIP, false, range, List.of(), update -> { }, () -> false);

        assertThat(results.get(0).status()).isEqualTo(Status.EMPTY_RANGE);
        assertThat(results.get(1).status()).isEqualTo(Status.OK);
        assertThat(results.get(1).entriesKept()).isEqualTo(16);
        assertThat(names(outDir)).containsExactly(TerminalLogExporter.targetName(BASE_NAME, Compression.GZIP, range));
    }

    @Test
    void aMissingSourceIsReportedFailedWithoutThrowing() throws Exception {
        Path gone = tempDir.resolve(BASE_NAME + ".gz");
        TerminalLogFile record = new TerminalLogFile(gone, tempDir,
            TerminalLogNaming.parse(gone.getFileName().toString()), 0L, null, null, false);

        Result result = TerminalLogExporter.exportOne(record, outDir.resolve("copy.log.gz"), Compression.GZIP,
            false, null, List.of(), bytes -> { }, () -> false);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.error()).isNotNull();
        assertThat(names(outDir)).isEmpty();
    }
}
