package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalRecordingScope;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.testng.SkipException;
import org.testng.annotations.Test;

class TerminalRecordingServiceTest {

    @Test
    void sanitizeFileNameKeepsOnlyPortableCharacters() {
        assertThat(TerminalRecordingService.sanitizeFileName("prod / root@host:22"))
            .isEqualTo("prod_root_host_22");
        assertThat(TerminalRecordingService.sanitizeFileName("   ")).isEqualTo("terminal");
    }

    @Test
    void createSessionUsesConfiguredDirectoryAndCompressedReplayFile() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recordings");
        try {
            GlobalSettings settings = new GlobalSettings();
            settings.setTerminalRecordingStoragePath(dir.toString());

            TerminalRecordingService service = new TerminalRecordingService();
            Path replayFile;
            try (TerminalRecordingSession session =
                     service.createSession(settings, "prod/server", "session-1234567890")) {
                replayFile = session.getReplayFile();
                session.start(TerminalRecordingScope.ACTIVE_SPLIT);
                session.recordScreenSnapshot("split-1", "compressed\n");
                session.stop();
            }

            assertThat(replayFile.getParent()).isEqualTo(dir.toAbsolutePath().normalize());
            assertThat(replayFile.getFileName().toString()).endsWith(".korttyrec.jsonl.gz");
            assertThat(Files.exists(replayFile)).isTrue();
            assertThat(readCompressedReplay(replayFile)).contains("\"type\":\"screen\"");
            assertThat(service.loadReplayFrames(replayFile).get(0).content()).isEqualTo("compressed\n");
        } finally {
            deleteTempFiles(dir);
        }
    }

    @Test
    void missingFfmpegPathIsReportedUnavailable() {
        assertThat(new TerminalRecordingService().isFfmpegAvailable("/no/such/kortty-ffmpeg")).isFalse();
    }

    @Test
    void timedReplayFramesUseReplayTimingAndSkipPausedTime() throws Exception {
        Path file = Files.createTempFile("kortty-timed", ".korttyrec.jsonl");
        try {
            Files.writeString(
                file,
                "{\"type\":\"session_created\",\"at\":\"2026-05-13T12:00:00Z\",\"connection\":\"prod\",\"formatVersion\":1}\n"
                    + "{\"type\":\"recording_start\",\"at\":\"2026-05-13T12:00:00Z\",\"scope\":\"ACTIVE_SPLIT\",\"segment\":1}\n"
                    + "{\"type\":\"screen\",\"at\":\"2026-05-13T12:00:00Z\",\"widget\":\"split-1\",\"content\":\"one\"}\n"
                    + "{\"type\":\"screen\",\"at\":\"2026-05-13T12:00:00.100Z\",\"widget\":\"split-1\",\"content\":\"two\"}\n"
                    + "{\"type\":\"auto_pause\",\"at\":\"2026-05-13T12:00:01.100Z\",\"idleMillis\":1000}\n"
                    + "{\"type\":\"auto_resume\",\"at\":\"2026-05-13T12:01:01.100Z\",\"source\":\"screen\"}\n"
                    + "{\"type\":\"screen\",\"at\":\"2026-05-13T12:01:01.100Z\",\"widget\":\"split-1\",\"content\":\"three\"}\n"
                    + "{\"type\":\"recording_stop\",\"at\":\"2026-05-13T12:01:01.600Z\",\"segment\":1}\n",
                StandardCharsets.UTF_8);

            List<TerminalRecordingReplayFrame> frames =
                new TerminalRecordingService().buildTimedReplayFrames(file);

            assertThat(frames).hasSize(3);
            assertThat(frames.get(0).content()).isEqualTo("one");
            assertThat(frames.get(0).durationSeconds()).isWithin(0.001).of(0.1);
            assertThat(frames.get(1).content()).isEqualTo("two");
            assertThat(frames.get(1).durationSeconds()).isWithin(0.001).of(1.0);
            assertThat(frames.get(2).content()).isEqualTo("three");
            assertThat(frames.get(2).durationSeconds()).isWithin(0.001).of(0.5);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void timedReplayFramesPreserveGeometryAndColorRuns() throws Exception {
        Path file = Files.createTempFile("kortty-styled", ".korttyrec.jsonl");
        try {
            Files.writeString(
                file,
                "{\"type\":\"session_created\",\"at\":\"2026-05-13T12:00:00Z\",\"connection\":\"prod\",\"formatVersion\":1}\n"
                    + "{\"type\":\"recording_start\",\"at\":\"2026-05-13T12:00:00Z\",\"scope\":\"ACTIVE_SPLIT\",\"segment\":1}\n"
                    + "{\"type\":\"screen\",\"at\":\"2026-05-13T12:00:00Z\",\"widget\":\"split-1\",\"content\":\"red\","
                    + "\"columns\":120,\"rows\":40,\"pixelWidth\":1600,\"pixelHeight\":900,"
                    + "\"styleRuns\":[{\"row\":0,\"column\":0,\"text\":\"red\",\"foreground\":\"#FF0000\","
                    + "\"background\":\"#000000\",\"options\":[\"BOLD\"]}]}\n"
                    + "{\"type\":\"recording_stop\",\"at\":\"2026-05-13T12:00:01Z\",\"segment\":1}\n",
                StandardCharsets.UTF_8);

            List<TerminalRecordingReplayFrame> frames =
                new TerminalRecordingService().buildTimedReplayFrames(file);

            assertThat(frames).hasSize(1);
            TerminalRecordingReplayFrame frame = frames.get(0);
            assertThat(frame.columns()).isEqualTo(120);
            assertThat(frame.rows()).isEqualTo(40);
            assertThat(frame.pixelWidth()).isEqualTo(1600);
            assertThat(frame.pixelHeight()).isEqualTo(900);
            assertThat(frame.hasColorData()).isTrue();
            assertThat(frame.styleRuns().get(0).foreground()).isEqualTo("#FF0000");
            assertThat(frame.styleRuns().get(0).options()).contains("BOLD");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void timedReplayFramesReadCompressedReplay() throws Exception {
        Path file = Files.createTempFile("kortty-timed", ".korttyrec.jsonl.gz");
        try {
            writeCompressedReplay(
                file,
                "{\"type\":\"session_created\",\"at\":\"2026-05-13T12:00:00Z\",\"connection\":\"prod\",\"formatVersion\":1}\n"
                    + "{\"type\":\"recording_start\",\"at\":\"2026-05-13T12:00:00Z\",\"scope\":\"ACTIVE_SPLIT\",\"segment\":1}\n"
                    + "{\"type\":\"screen\",\"at\":\"2026-05-13T12:00:00Z\",\"widget\":\"split-1\",\"content\":\"one\"}\n"
                    + "{\"type\":\"recording_stop\",\"at\":\"2026-05-13T12:00:01Z\",\"segment\":1}\n");

            List<TerminalRecordingReplayFrame> frames =
                new TerminalRecordingService().buildTimedReplayFrames(file);

            assertThat(frames).hasSize(1);
            assertThat(frames.get(0).content()).isEqualTo("one");
            assertThat(frames.get(0).durationSeconds()).isWithin(0.001).of(1.0);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void sliceReplayFramesKeepsOnlyRequestedRange() throws Exception {
        List<TerminalRecordingReplayFrame> frames = List.of(
            new TerminalRecordingReplayFrame("one", 2.0),
            new TerminalRecordingReplayFrame("two", 3.0),
            new TerminalRecordingReplayFrame("three", 4.0));

        List<TerminalRecordingReplayFrame> sliced = new TerminalRecordingService().sliceReplayFrames(
            frames,
            TerminalRecordingTimeRange.custom(1.0, 6.0));

        assertThat(sliced).hasSize(3);
        assertThat(sliced.get(0).content()).isEqualTo("one");
        assertThat(sliced.get(0).durationSeconds()).isWithin(0.0001).of(1.0);
        assertThat(sliced.get(1).content()).isEqualTo("two");
        assertThat(sliced.get(1).durationSeconds()).isWithin(0.0001).of(3.0);
        assertThat(sliced.get(2).content()).isEqualTo("three");
        assertThat(sliced.get(2).durationSeconds()).isWithin(0.0001).of(1.0);
    }

    @Test
    void renderLayoutUsesRecordedTerminalSizeWhenAvailable() {
        TerminalRecordingReplayFrame frame = new TerminalRecordingReplayFrame(
            new TerminalRecordingScreenSnapshot("wide", 200, 60, 1801, 901, List.of()),
            1.0);

        TerminalRecordingService.RenderLayout layout =
            TerminalRecordingService.resolveRenderLayout(List.of(frame));

        assertThat(layout.width()).isAtLeast(1802);
        assertThat(layout.height()).isAtLeast(902);
        assertThat(layout.width() % 2).isEqualTo(0);
        assertThat(layout.height() % 2).isEqualTo(0);
    }

    @Test
    void exportReplayToWebmUsesConfiguredFfmpegTestDouble() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("Unix shell test double is not portable to Windows");
        }

        Path dir = Files.createTempDirectory("kortty-recording-export-test");
        try {
            Path ffmpegTestDouble = dir.resolve("ffmpeg-test-double.sh");
            Files.writeString(
                ffmpegTestDouble,
                "#!/bin/sh\n"
                    + "if [ \"$1\" = \"-version\" ]; then\n"
                    + "  echo \"ffmpeg test double\"\n"
                    + "  exit 0\n"
                    + "fi\n"
                    + "echo 'out_time=00:00:00.500000'\n"
                    + "echo 'progress=continue'\n"
                    + "output=\"\"\n"
                    + "for arg in \"$@\"; do\n"
                    + "  output=\"$arg\"\n"
                    + "done\n"
                    + "printf 'webm-test-double' > \"$output\"\n"
                    + "exit 0\n",
                StandardCharsets.UTF_8);
            assertThat(ffmpegTestDouble.toFile().setExecutable(true)).isTrue();

            Path replayFile = dir.resolve("session.korttyrec.jsonl");
            try (TerminalRecordingSession session =
                     new TerminalRecordingSession(replayFile, "prod", false, 20, java.time.Clock.systemUTC())) {
                session.start(TerminalRecordingScope.ACTIVE_SPLIT);
                session.recordScreenSnapshot("split-1", "hello\nworld");
                session.stop();
            }

            TerminalRecordingService service = new TerminalRecordingService();
            Path webmFile = dir.resolve("session.webm");
            List<TerminalRecordingService.ExportProgress> progressEvents =
                Collections.synchronizedList(new ArrayList<>());
            assertThat(service.isFfmpegAvailable(ffmpegTestDouble.toString())).isTrue();
            assertThat(service.exportReplayToWebm(
                replayFile,
                webmFile,
                ffmpegTestDouble.toString(),
                progressEvents::add)).isEqualTo(webmFile);
            assertThat(Files.readString(webmFile)).isEqualTo("webm-test-double");
            assertThat(progressEvents.stream().map(TerminalRecordingService.ExportProgress::phase).toList())
                .containsAtLeast(
                    TerminalRecordingService.ExportPhase.PREPARING,
                    TerminalRecordingService.ExportPhase.RENDERING,
                    TerminalRecordingService.ExportPhase.ENCODING,
                    TerminalRecordingService.ExportPhase.FINALIZING);
            assertThat(progressEvents.get(progressEvents.size() - 1).fraction()).isEqualTo(1.0);
        } finally {
            deleteTempFiles(dir);
        }
    }

    @Test
    void exportReplayToMkvUsesFfv1Arguments() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("Unix shell test double is not portable to Windows");
        }

        Path dir = Files.createTempDirectory("kortty-recording-mkv-export-test");
        try {
            Path argsFile = dir.resolve("ffmpeg-args.txt");
            Path ffmpegTestDouble = dir.resolve("ffmpeg-test-double.sh");
            Files.writeString(
                ffmpegTestDouble,
                "#!/bin/sh\n"
                    + "if [ \"$1\" = \"-version\" ]; then\n"
                    + "  echo \"ffmpeg test double\"\n"
                    + "  exit 0\n"
                    + "fi\n"
                    + "printf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                    + "echo 'out_time=00:00:00.500000'\n"
                    + "output=\"\"\n"
                    + "for arg in \"$@\"; do\n"
                    + "  output=\"$arg\"\n"
                    + "done\n"
                    + "printf 'mkv-test-double' > \"$output\"\n"
                    + "exit 0\n",
                StandardCharsets.UTF_8);
            assertThat(ffmpegTestDouble.toFile().setExecutable(true)).isTrue();

            Path replayFile = dir.resolve("session.korttyrec.jsonl");
            try (TerminalRecordingSession session =
                     new TerminalRecordingSession(replayFile, "prod", false, 20, java.time.Clock.systemUTC())) {
                session.start(TerminalRecordingScope.ACTIVE_SPLIT);
                session.recordScreenSnapshot("split-1", "hello\nworld");
                session.stop();
            }

            TerminalRecordingService service = new TerminalRecordingService();
            Path mkvFile = dir.resolve("session.mkv");
            assertThat(service.exportReplay(
                replayFile,
                mkvFile,
                ffmpegTestDouble.toString(),
                new TerminalRecordingExportOptions(
                    TerminalRecordingExportFormat.MKV,
                    TerminalRecordingTimeRange.all(),
                    false),
                progress -> {
                })).isEqualTo(mkvFile.toAbsolutePath().normalize());

            String args = Files.readString(argsFile);
            assertThat(args).contains("-c:v");
            assertThat(args).contains("ffv1");
            assertThat(args).contains("-threads");
            assertThat(Files.readString(mkvFile)).isEqualTo("mkv-test-double");
        } finally {
            deleteTempFiles(dir);
        }
    }

    @Test
    void renameReplayFileSanitizesNameAndKeepsExtension() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recording-rename-test");
        try {
            Path replayFile = dir.resolve("old.korttyrec.jsonl");
            Files.writeString(replayFile, "{}", StandardCharsets.UTF_8);

            Path renamed = new TerminalRecordingService()
                .renameReplayFile(replayFile, "prod / root@host:22");

            assertThat(renamed.getFileName().toString()).isEqualTo("prod_root_host_22.korttyrec.jsonl");
            assertThat(Files.exists(renamed)).isTrue();
            assertThat(Files.exists(replayFile)).isFalse();
        } finally {
            deleteTempFiles(dir);
        }
    }

    @Test
    void renameCompressedReplayFileKeepsCompressedExtension() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recording-rename-compressed-test");
        try {
            Path replayFile = dir.resolve("old.korttyrec.jsonl.gz");
            Files.writeString(replayFile, "not-read-during-rename", StandardCharsets.UTF_8);

            Path renamed = new TerminalRecordingService()
                .renameReplayFile(replayFile, "prod / root@host:22.korttyrec.jsonl.gz");

            assertThat(renamed.getFileName().toString()).isEqualTo("prod_root_host_22.korttyrec.jsonl.gz");
            assertThat(Files.exists(renamed)).isTrue();
            assertThat(Files.exists(replayFile)).isFalse();
        } finally {
            deleteTempFiles(dir);
        }
    }

    @Test
    void deleteReplayFileDeletesSelectedReplayOnly() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recording-delete-test");
        try {
            Path replayFile = dir.resolve("session.korttyrec.jsonl");
            Files.writeString(replayFile, "{}", StandardCharsets.UTF_8);

            new TerminalRecordingService().deleteReplayFile(replayFile);

            assertThat(Files.exists(replayFile)).isFalse();
        } finally {
            deleteTempFiles(dir);
        }
    }

    private static void deleteTempFiles(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void writeCompressedReplay(Path file, String replay) throws Exception {
        try (Writer writer = new OutputStreamWriter(
            new GZIPOutputStream(Files.newOutputStream(file)),
            StandardCharsets.UTF_8)) {
            writer.write(replay);
        }
    }

    private static String readCompressedReplay(Path file) throws Exception {
        StringBuilder replay = new StringBuilder();
        try (Reader reader = new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(file)),
            StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                replay.append(buffer, 0, read);
            }
        }
        return replay.toString();
    }
}
