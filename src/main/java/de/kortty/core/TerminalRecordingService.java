package de.kortty.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalRecordingScope;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerminalRecordingService {

    private static final Logger logger = LoggerFactory.getLogger(TerminalRecordingService.class);
    static final String LEGACY_REPLAY_EXTENSION = ".korttyrec.jsonl";
    static final String COMPRESSED_REPLAY_EXTENSION = ".korttyrec.jsonl.gz";
    private static final DateTimeFormatter FILE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final int FRAME_PADDING_X = 32;
    private static final int FRAME_PADDING_Y = 24;
    private static final int EXPORT_FONT_SIZE = 20;
    private static final int MAX_RENDER_THREADS = 4;
    private static final double MIN_FRAME_DURATION_SECONDS = 0.001;
    private static final double FALLBACK_LAST_FRAME_DURATION_SECONDS = 1.0;
    private static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;
    private static final int FFMPEG_EXPORT_TIMEOUT_SECONDS = 300;
    private static final double RENDER_PROGRESS_WEIGHT = 0.70;
    private static final double ENCODE_PROGRESS_WEIGHT = 0.29;

    public enum ExportPhase {
        PREPARING,
        RENDERING,
        ENCODING,
        FINALIZING
    }

    public record ExportProgress(ExportPhase phase, int current, int total, double fraction) {
    }

    @FunctionalInterface
    public interface ExportProgressListener {
        void onProgress(ExportProgress progress);
    }

    public TerminalRecordingSession createSession(
        GlobalSettings settings,
        String connectionName,
        String tabSessionId) throws IOException {
        return createSession(settings, connectionName, tabSessionId, Clock.systemDefaultZone());
    }

    TerminalRecordingSession createSession(
        GlobalSettings settings,
        String connectionName,
        String tabSessionId,
        Clock clock) throws IOException {
        Objects.requireNonNull(clock, "clock must not be null");
        Path directory = resolveRecordingDirectory(settings);
        Files.createDirectories(directory);
        Path replayFile = nextReplayFile(directory, connectionName, tabSessionId);
        return new TerminalRecordingSession(
            replayFile,
            connectionName,
            settings != null && settings.isTerminalRecordingAutoPauseEnabled(),
            settings != null ? settings.getTerminalRecordingIdlePauseSeconds() : 20,
            clock);
    }

    public static Path resolveRecordingDirectory(GlobalSettings settings) {
        String configured = settings != null ? settings.getTerminalRecordingStoragePath() : null;
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".kortty", "recordings")
            .toAbsolutePath()
            .normalize();
    }

    public static String sanitizeFileName(String value) {
        String normalized = value != null ? value.trim() : "";
        if (normalized.isEmpty()) {
            return "terminal";
        }
        String sanitized = normalized.replaceAll("[^A-Za-z0-9._-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return sanitized.isEmpty() ? "terminal" : sanitized;
    }

    private Path nextReplayFile(Path directory, String connectionName, String tabSessionId) {
        String baseName = sanitizeFileName(connectionName);
        String sessionPart = sanitizeFileName(tabSessionId);
        if (sessionPart.length() > 12) {
            sessionPart = sessionPart.substring(0, 12);
        }
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        Path candidate = directory.resolve(baseName + "-" + timestamp + "-" + sessionPart + COMPRESSED_REPLAY_EXTENSION);
        int counter = 2;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(
                baseName + "-" + timestamp + "-" + sessionPart + "-" + counter + COMPRESSED_REPLAY_EXTENSION);
            counter++;
        }
        return candidate;
    }

    public List<Path> listReplayFiles(GlobalSettings settings) throws IOException {
        Path directory = resolveRecordingDirectory(settings);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream
                .filter(this::isReplayFile)
                .sorted(Comparator.comparing(this::lastModifiedMillis).reversed())
                .toList();
        }
    }

    public Path renameReplayFile(Path replayFile, String requestedName) throws IOException {
        Path source = validateReplayFile(replayFile);
        String baseName = normalizeReplayBaseName(requestedName);
        Path target = source.getParent().resolve(baseName + replayExtension(source)).toAbsolutePath().normalize();
        if (!target.getParent().equals(source.getParent())) {
            throw new IOException("Replay file must stay in its recording folder");
        }
        if (Files.exists(target) && !target.equals(source)) {
            throw new IOException("Replay file already exists: " + target.getFileName());
        }
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(source, target);
        }
    }

    public void deleteReplayFile(Path replayFile) throws IOException {
        Files.delete(validateReplayFile(replayFile));
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    public boolean isFfmpegAvailable(String configuredPath) {
        String executable = normalizeFfmpegExecutable(configuredPath);
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-version")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException e) {
            logger.debug("ffmpeg is not available at '{}': {}", executable, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public Path exportReplayToWebm(Path replayFile, Path webmFile, String configuredFfmpegPath)
        throws IOException, InterruptedException {
        return exportReplayToWebm(replayFile, webmFile, configuredFfmpegPath, null);
    }

    public Path exportReplayToWebm(
        Path replayFile,
        Path webmFile,
        String configuredFfmpegPath,
        ExportProgressListener progressListener)
        throws IOException, InterruptedException {
        return exportReplay(
            replayFile,
            webmFile,
            configuredFfmpegPath,
            TerminalRecordingExportOptions.webmDefaults(),
            progressListener);
    }

    public Path exportReplay(
        Path replayFile,
        Path outputFile,
        String configuredFfmpegPath,
        TerminalRecordingExportOptions options,
        ExportProgressListener progressListener)
        throws IOException, InterruptedException {
        TerminalRecordingExportOptions safeOptions = options != null
            ? options
            : TerminalRecordingExportOptions.webmDefaults();
        if (replayFile == null || !Files.isRegularFile(replayFile)) {
            throw new IOException("Replay file does not exist: " + replayFile);
        }
        if (outputFile == null) {
            throw new IOException("Video output file is not configured");
        }
        String ffmpeg = normalizeFfmpegExecutable(configuredFfmpegPath);
        Path normalizedOutput = outputFile.toAbsolutePath().normalize();
        Path parent = normalizedOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path frameDir = Files.createTempDirectory("kortty-recording-export");
        try {
            reportProgress(progressListener, ExportPhase.PREPARING, 0, 0, 0.0);
            RenderedReplay renderedReplay = renderReplayFrames(replayFile, frameDir, safeOptions, progressListener);
            Process process = new ProcessBuilder(buildFfmpegCommand(
                    ffmpeg,
                    renderedReplay.concatFile(),
                    normalizedOutput,
                    safeOptions.format()))
                .redirectErrorStream(true)
                .start();
            try {
                BoundedProcessOutput output = new BoundedProcessOutput(MAX_PROCESS_OUTPUT_BYTES);
                Thread outputReader = new Thread(
                    () -> output.readFrom(
                        process.getInputStream(),
                        line -> handleFfmpegProgressLine(line, renderedReplay.totalDurationSeconds(), progressListener)),
                    "TerminalRecordingFfmpegOutput");
                outputReader.setDaemon(true);
                outputReader.start();
                boolean finished = process.waitFor(FFMPEG_EXPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    outputReader.join(1000);
                    throw new IOException("ffmpeg timed out after " + FFMPEG_EXPORT_TIMEOUT_SECONDS
                        + " seconds: " + output.asUtf8String());
                }
                outputReader.join(1000);
                int exit = process.exitValue();
                if (exit != 0) {
                    throw new IOException("ffmpeg failed with exit code " + exit + ": " + output.asUtf8String());
                }
                if (renderedReplay.frameCount() == 0 || !Files.isRegularFile(normalizedOutput)) {
                    throw new IOException("ffmpeg did not create a video file");
                }
                reportProgress(progressListener, ExportPhase.FINALIZING, 1, 1, 1.0);
                return normalizedOutput;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } finally {
            deleteRecursivelyAsync(frameDir);
        }
    }

    private static List<String> buildFfmpegCommand(
        String ffmpeg,
        Path concatFile,
        Path outputFile,
        TerminalRecordingExportFormat format) {
        List<String> command = new ArrayList<>(List.of(
            ffmpeg,
            "-y",
            "-nostats",
            "-progress", "pipe:1",
            "-f", "concat",
            "-safe", "0",
            "-i", concatFile.toString(),
            "-fps_mode:v", "vfr",
            "-threads", "0"));
        if (format == TerminalRecordingExportFormat.MKV) {
            command.addAll(List.of(
                "-c:v", "ffv1",
                "-level", "3",
                "-g", "1"));
        } else {
            command.addAll(List.of(
                "-c:v", "libvpx-vp9",
                "-row-mt", "1",
                "-cpu-used", "4",
                "-pix_fmt", "yuv420p"));
        }
        command.add(outputFile.toString());
        return command;
    }

    private RenderedReplay renderReplayFrames(
        Path replayFile,
        Path frameDir,
        TerminalRecordingExportOptions options,
        ExportProgressListener progressListener) throws IOException, InterruptedException {
        List<TerminalRecordingReplayFrame> frames = sliceReplayFrames(
            buildTimedReplayFrames(replayFile),
            options.timeRange());
        if (frames.isEmpty()) {
            frames = List.of(new TerminalRecordingReplayFrame(
                "No terminal screen frames were recorded.",
                FALLBACK_LAST_FRAME_DURATION_SECONDS));
        }
        double totalDurationSeconds = frames.stream()
            .mapToDouble(TerminalRecordingReplayTimeline::durationSeconds)
            .sum();
        Path concatFile = frameDir.resolve("frames.txt");
        RenderLayout layout = resolveRenderLayout(frames);
        List<Path> frameFiles = renderFrameImages(frameDir, frames, layout, options.includeColor(), progressListener);
        try (BufferedWriter writer = Files.newBufferedWriter(concatFile, StandardCharsets.UTF_8)) {
            for (int i = 0; i < frames.size(); i++) {
                TerminalRecordingReplayFrame frame = frames.get(i);
                Path frameFile = frameFiles.get(i);
                writer.write("file '");
                writer.write(escapeConcatPath(frameFile));
                writer.write("'");
                writer.newLine();
                writer.write("duration ");
                writer.write(formatDuration(frame.durationSeconds()));
                writer.newLine();
            }
            if (!frameFiles.isEmpty()) {
                writer.write("file '");
                writer.write(escapeConcatPath(frameFiles.get(frameFiles.size() - 1)));
                writer.write("'");
                writer.newLine();
            }
        }
        return new RenderedReplay(frames.size(), concatFile, totalDurationSeconds);
    }

    public List<TerminalRecordingReplayFrame> loadReplayFrames(Path replayFile) throws IOException {
        validateReplayFile(replayFile);
        return buildTimedReplayFrames(replayFile);
    }

    List<TerminalRecordingReplayFrame> buildTimedReplayFrames(Path replayFile) throws IOException {
        ReplayTimeline timeline = readReplayTimeline(replayFile);
        List<ReplayScreenFrame> frames = timeline.frames();
        if (frames.isEmpty()) {
            return List.of();
        }

        List<TerminalRecordingReplayFrame> timedFrames = new ArrayList<>();
        long firstFrameNanos = frames.get(0).playbackNanos();
        long normalizedEndNanos = Math.max(
            0,
            timeline.endPlaybackNanos() - firstFrameNanos);
        for (int i = 0; i < frames.size(); i++) {
            ReplayScreenFrame frame = frames.get(i);
            long currentNanos = Math.max(0, frame.playbackNanos() - firstFrameNanos);
            long nextNanos = i + 1 < frames.size()
                ? Math.max(0, frames.get(i + 1).playbackNanos() - firstFrameNanos)
                : normalizedEndNanos;
            double durationSeconds = i + 1 < frames.size() || nextNanos > currentNanos
                ? durationSeconds(nextNanos - currentNanos)
                : FALLBACK_LAST_FRAME_DURATION_SECONDS;
            timedFrames.add(new TerminalRecordingReplayFrame(frame.snapshot(), durationSeconds));
        }
        return timedFrames;
    }

    List<TerminalRecordingReplayFrame> sliceReplayFrames(
        List<TerminalRecordingReplayFrame> frames,
        TerminalRecordingTimeRange timeRange) throws IOException {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        TerminalRecordingReplayTimeline timeline = new TerminalRecordingReplayTimeline(frames);
        TerminalRecordingTimeRange range = (timeRange != null ? timeRange : TerminalRecordingTimeRange.all())
            .normalized(timeline.totalDurationSeconds());
        if (!range.isValidFor(timeline.totalDurationSeconds())) {
            throw new IOException("Export time range is outside the replay duration");
        }
        if (range.isAll()) {
            return frames;
        }

        List<TerminalRecordingReplayFrame> sliced = new ArrayList<>();
        double cursorSeconds = 0.0;
        for (TerminalRecordingReplayFrame frame : frames) {
            double frameDuration = TerminalRecordingReplayTimeline.durationSeconds(frame);
            double frameStart = cursorSeconds;
            double frameEnd = frameStart + frameDuration;
            double overlapStart = Math.max(frameStart, range.startSeconds());
            double overlapEnd = Math.min(frameEnd, range.endSeconds());
            if (overlapEnd > overlapStart) {
                sliced.add(new TerminalRecordingReplayFrame(frame.snapshot(), overlapEnd - overlapStart));
            }
            cursorSeconds = frameEnd;
            if (cursorSeconds >= range.endSeconds()) {
                break;
            }
        }
        return sliced;
    }

    private ReplayTimeline readReplayTimeline(Path replayFile) throws IOException {
        List<ReplayScreenFrame> frames = new ArrayList<>();
        boolean recording = false;
        boolean paused = false;
        Instant lastActiveAt = null;
        long playbackNanos = 0L;
        long endPlaybackNanos = 0L;
        TerminalRecordingScreenSnapshot previousSnapshot = null;

        try (BufferedReader reader = new BufferedReader(openReplayReader(replayFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ReplayEvent event = parseReplayEvent(line);
                if (event == null) {
                    continue;
                }

                if (recording && !paused && lastActiveAt != null) {
                    playbackNanos += positiveNanosBetween(lastActiveAt, event.at());
                    lastActiveAt = event.at();
                    endPlaybackNanos = playbackNanos;
                }

                switch (event.type()) {
                    case "recording_start" -> {
                        recording = true;
                        paused = false;
                        lastActiveAt = event.at();
                    }
                    case "auto_pause" -> {
                        if (recording && !paused) {
                            paused = true;
                            lastActiveAt = null;
                        }
                    }
                    case "auto_resume" -> {
                        if (recording) {
                            paused = false;
                            lastActiveAt = event.at();
                        }
                    }
                    case "recording_stop" -> {
                        recording = false;
                        paused = false;
                        lastActiveAt = null;
                        endPlaybackNanos = playbackNanos;
                    }
                    case "screen" -> {
                        if (recording && event.snapshot() != null && !event.snapshot().equals(previousSnapshot)) {
                            frames.add(new ReplayScreenFrame(event.snapshot(), playbackNanos));
                            previousSnapshot = event.snapshot();
                        }
                    }
                    default -> {
                        // Non-visual events still advance the active playback clock above.
                    }
                }
            }
        }
        return new ReplayTimeline(frames, endPlaybackNanos);
    }

    private static ReplayEvent parseReplayEvent(String replayLine) {
        try {
            JsonElement root = JsonParser.parseString(replayLine);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject event = root.getAsJsonObject();
            JsonElement type = event.get("type");
            JsonElement at = event.get("at");
            if (type == null || at == null || !type.isJsonPrimitive() || !at.isJsonPrimitive()) {
                return null;
            }
            String eventType = type.getAsString();
            Instant eventAt = Instant.parse(at.getAsString());
            return new ReplayEvent(
                eventType,
                eventAt,
                parseScreenSnapshot(event));
        } catch (DateTimeParseException | JsonSyntaxException | IllegalStateException e) {
            logger.debug("Ignoring malformed replay event during export: {}", e.getMessage());
            return null;
        }
    }

    private static TerminalRecordingScreenSnapshot parseScreenSnapshot(JsonObject event) {
        JsonElement content = event.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            return null;
        }
        return new TerminalRecordingScreenSnapshot(
            content.getAsString(),
            intProperty(event, "columns"),
            intProperty(event, "rows"),
            intProperty(event, "pixelWidth"),
            intProperty(event, "pixelHeight"),
            parseStyleRuns(event.get("styleRuns")));
    }

    private static List<TerminalRecordingStyleRun> parseStyleRuns(JsonElement styleRunsElement) {
        if (styleRunsElement == null || !styleRunsElement.isJsonArray()) {
            return List.of();
        }
        List<TerminalRecordingStyleRun> styleRuns = new ArrayList<>();
        JsonArray array = styleRunsElement.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject run = element.getAsJsonObject();
            String text = stringProperty(run, "text");
            if (text == null || text.isEmpty()) {
                continue;
            }
            styleRuns.add(new TerminalRecordingStyleRun(
                intProperty(run, "row"),
                intProperty(run, "column"),
                text,
                stringProperty(run, "foreground"),
                stringProperty(run, "background"),
                stringArrayProperty(run.get("options"))));
        }
        return styleRuns;
    }

    private static int intProperty(JsonObject object, String property) {
        JsonElement element = object.get(property);
        if (element == null || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | IllegalStateException e) {
            return 0;
        }
    }

    private static String stringProperty(JsonObject object, String property) {
        JsonElement element = object.get(property);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static List<String> stringArrayProperty(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (value.isJsonPrimitive()) {
                values.add(value.getAsString());
            }
        }
        return values;
    }

    private List<Path> renderFrameImages(
        Path frameDir,
        List<TerminalRecordingReplayFrame> frames,
        RenderLayout layout,
        boolean includeColor,
        ExportProgressListener progressListener) throws IOException, InterruptedException {
        int threadCount = renderThreadCount();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "TerminalRecordingFrameRenderer");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Path>> futures = new ArrayList<>(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                TerminalRecordingReplayFrame frame = frames.get(i);
                Path output = frameDir.resolve(String.format(Locale.ROOT, "frame-%05d.png", i + 1));
                futures.add(executor.submit(() -> {
                    renderFrame(frame, layout, includeColor, output);
                    return output;
                }));
            }

            List<Path> renderedFiles = new ArrayList<>(frames.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    renderedFiles.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new IOException("Could not render terminal recording frame", cause);
                }
                double renderProgress = (i + 1) / (double) frames.size();
                reportProgress(
                    progressListener,
                    ExportPhase.RENDERING,
                    i + 1,
                    frames.size(),
                    renderProgress * RENDER_PROGRESS_WEIGHT);
            }
            return renderedFiles;
        } finally {
            executor.shutdownNow();
        }
    }

    private static int renderThreadCount() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, MAX_RENDER_THREADS));
    }

    static RenderLayout resolveRenderLayout(List<TerminalRecordingReplayFrame> frames) {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, EXPORT_FONT_SIZE);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = probe.createGraphics();
        try {
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            int maxColumns = 1;
            int maxRows = 1;
            int maxPixelWidth = 0;
            int maxPixelHeight = 0;
            for (TerminalRecordingReplayFrame frame : frames) {
                maxColumns = Math.max(maxColumns, frame.columns());
                maxRows = Math.max(maxRows, frame.rows());
                maxPixelWidth = Math.max(maxPixelWidth, frame.pixelWidth());
                maxPixelHeight = Math.max(maxPixelHeight, frame.pixelHeight());
                String[] lines = splitScreenLines(frame.content());
                maxRows = Math.max(maxRows, lines.length);
                for (String line : lines) {
                    maxColumns = Math.max(maxColumns, line != null ? line.length() : 0);
                }
                for (TerminalRecordingStyleRun run : frame.styleRuns()) {
                    maxColumns = Math.max(maxColumns, run.column() + run.text().length());
                    maxRows = Math.max(maxRows, run.row() + 1);
                }
            }
            int computedWidth = FRAME_PADDING_X * 2 + Math.max(1, metrics.charWidth('W')) * maxColumns;
            int computedHeight = FRAME_PADDING_Y * 2 + metrics.getHeight() * maxRows;
            return new RenderLayout(
                even(Math.max(computedWidth, maxPixelWidth)),
                even(Math.max(computedHeight, maxPixelHeight)),
                font,
                Math.max(1, metrics.charWidth('W')),
                metrics.getHeight(),
                metrics.getAscent());
        } finally {
            graphics.dispose();
        }
    }

    private void renderFrame(
        TerminalRecordingReplayFrame frame,
        RenderLayout layout,
        boolean includeColor,
        Path output) throws IOException {
        BufferedImage image = new BufferedImage(layout.width(), layout.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, layout.width(), layout.height());
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(layout.font());
            if (includeColor && frame.hasColorData()) {
                renderStyledFrame(graphics, frame, layout);
            } else {
                renderPlainFrame(graphics, frame.content(), layout);
            }
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void renderPlainFrame(Graphics2D graphics, String screen, RenderLayout layout) {
        graphics.setColor(new Color(220, 235, 220));
        String[] lines = splitScreenLines(screen);
        for (int row = 0; row < lines.length; row++) {
            graphics.drawString(lines[row], FRAME_PADDING_X, baseline(row, layout));
        }
    }

    private static void renderStyledFrame(
        Graphics2D graphics,
        TerminalRecordingReplayFrame frame,
        RenderLayout layout) {
        for (TerminalRecordingStyleRun run : frame.styleRuns()) {
            int x = FRAME_PADDING_X + (run.column() * layout.cellWidth());
            int y = FRAME_PADDING_Y + (run.row() * layout.lineHeight());
            Color foreground = parseColor(run.foreground(), new Color(220, 235, 220));
            Color background = parseColor(run.background(), Color.BLACK);
            if (run.options().contains("INVERSE")) {
                Color swap = foreground;
                foreground = background;
                background = swap;
            }
            graphics.setColor(background);
            graphics.fillRect(x, y, Math.max(layout.cellWidth(), layout.cellWidth() * run.text().length()), layout.lineHeight());
            graphics.setColor(foreground);
            graphics.setFont(run.options().contains("BOLD")
                ? layout.font().deriveFont(Font.BOLD)
                : layout.font());
            if (!run.options().contains("HIDDEN")) {
                graphics.drawString(run.text(), x, baseline(run.row(), layout));
            }
        }
        graphics.setFont(layout.font());
    }

    private static String[] splitScreenLines(String screen) {
        return (screen != null ? screen : "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split("\n", -1);
    }

    private static int baseline(int row, RenderLayout layout) {
        return FRAME_PADDING_Y + (row * layout.lineHeight()) + layout.ascent();
    }

    private static int even(int value) {
        int safe = Math.max(2, value);
        return safe % 2 == 0 ? safe : safe + 1;
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Color.decode(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long positiveNanosBetween(Instant start, Instant end) {
        long nanos = Duration.between(start, end).toNanos();
        return Math.max(0L, nanos);
    }

    private static double durationSeconds(long durationNanos) {
        return Math.max(MIN_FRAME_DURATION_SECONDS, durationNanos / 1_000_000_000.0);
    }

    private static String formatDuration(double durationSeconds) {
        return String.format(Locale.ROOT, "%.6f", Math.max(MIN_FRAME_DURATION_SECONDS, durationSeconds));
    }

    private static String escapeConcatPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("'", "'\\''");
    }

    private Path validateReplayFile(Path replayFile) throws IOException {
        if (replayFile == null) {
            throw new IOException("Replay file is not selected");
        }
        Path normalized = replayFile.toAbsolutePath().normalize();
        if (!isReplayFile(normalized) || !Files.isRegularFile(normalized)) {
            throw new IOException("Replay file does not exist: " + replayFile);
        }
        return normalized;
    }

    private boolean isReplayFile(Path path) {
        return path != null && replayExtension(path) != null;
    }

    private static String normalizeReplayBaseName(String requestedName) throws IOException {
        String name = requestedName != null ? requestedName.trim() : "";
        String extension = replayExtension(name);
        if (extension != null) {
            name = name.substring(0, name.length() - extension.length());
        }
        if (name.isBlank()) {
            throw new IOException("Replay file name is empty");
        }
        String sanitized = sanitizeFileName(name);
        if (sanitized.isBlank()) {
            throw new IOException("Replay file name is empty");
        }
        return sanitized;
    }

    static boolean isCompressedReplayFile(Path path) {
        return path != null && path.getFileName().toString().endsWith(COMPRESSED_REPLAY_EXTENSION);
    }

    public static String replayExtension(Path path) {
        return path != null ? replayExtension(path.getFileName().toString()) : null;
    }

    public static String replayExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        if (fileName.endsWith(COMPRESSED_REPLAY_EXTENSION)) {
            return COMPRESSED_REPLAY_EXTENSION;
        }
        return fileName.endsWith(LEGACY_REPLAY_EXTENSION) ? LEGACY_REPLAY_EXTENSION : null;
    }

    private static Reader openReplayReader(Path replayFile) throws IOException {
        InputStream input = Files.newInputStream(replayFile);
        try {
            InputStream replayInput = isCompressedReplayFile(replayFile)
                ? new GZIPInputStream(input)
                : input;
            return new InputStreamReader(replayInput, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            try {
                input.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    private static String normalizeFfmpegExecutable(String configuredPath) {
        String trimmed = configuredPath != null ? configuredPath.trim() : "";
        return trimmed.isEmpty() ? "ffmpeg" : trimmed;
    }

    private static void handleFfmpegProgressLine(
        String line,
        double totalDurationSeconds,
        ExportProgressListener progressListener) {
        if (line == null || !line.startsWith("out_time=") || totalDurationSeconds <= 0.0) {
            return;
        }
        double encodedSeconds = parseFfmpegOutTimeSeconds(line.substring("out_time=".length()));
        if (encodedSeconds < 0.0) {
            return;
        }
        double encodeProgress = Math.min(1.0, encodedSeconds / totalDurationSeconds);
        reportProgress(
            progressListener,
            ExportPhase.ENCODING,
            (int) Math.round(Math.min(encodedSeconds, totalDurationSeconds)),
            (int) Math.round(totalDurationSeconds),
            RENDER_PROGRESS_WEIGHT + (encodeProgress * ENCODE_PROGRESS_WEIGHT));
    }

    private static double parseFfmpegOutTimeSeconds(String value) {
        if (value == null) {
            return -1.0;
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 3) {
            return -1.0;
        }
        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            return (hours * 3600.0) + (minutes * 60.0) + seconds;
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    private static void reportProgress(
        ExportProgressListener listener,
        ExportPhase phase,
        int current,
        int total,
        double fraction) {
        if (listener == null) {
            return;
        }
        double safeFraction = Double.isFinite(fraction)
            ? Math.max(0.0, Math.min(1.0, fraction))
            : 0.0;
        try {
            listener.onProgress(new ExportProgress(phase, current, total, safeFraction));
        } catch (RuntimeException e) {
            logger.debug("Terminal recording export progress listener failed: {}", e.getMessage());
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.debug("Could not delete temporary export file '{}': {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.debug("Could not clean temporary export directory '{}': {}", directory, e.getMessage());
        }
    }

    private static void deleteRecursivelyAsync(Path directory) {
        if (directory == null) {
            return;
        }
        Thread cleanupThread = new Thread(
            () -> deleteRecursively(directory),
            "TerminalRecordingExportCleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private record RenderedReplay(int frameCount, Path concatFile, double totalDurationSeconds) {
    }

    record RenderLayout(
        int width,
        int height,
        Font font,
        int cellWidth,
        int lineHeight,
        int ascent) {
    }

    private record ReplayTimeline(List<ReplayScreenFrame> frames, long endPlaybackNanos) {
    }

    private record ReplayScreenFrame(TerminalRecordingScreenSnapshot snapshot, long playbackNanos) {
    }

    private record ReplayEvent(String type, Instant at, TerminalRecordingScreenSnapshot snapshot) {
    }

    private static final class BoundedProcessOutput {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int maxBytes;
        private boolean truncated;
        private IOException readFailure;

        private BoundedProcessOutput(int maxBytes) {
            this.maxBytes = Math.max(1, maxBytes);
        }

        private void readFrom(InputStream inputStream, Consumer<String> lineConsumer) {
            try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLine(line);
                    if (lineConsumer != null) {
                        lineConsumer.accept(line);
                    }
                }
            } catch (IOException e) {
                synchronized (this) {
                    readFailure = e;
                }
            }
        }

        private void appendLine(String line) {
            byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            append(bytes, bytes.length);
        }

        private synchronized void append(byte[] chunk, int length) {
            int remaining = maxBytes - buffer.size();
            if (remaining > 0) {
                buffer.write(chunk, 0, Math.min(length, remaining));
            }
            if (length > remaining) {
                truncated = true;
            }
        }

        private synchronized String asUtf8String() {
            String text = buffer.toString(StandardCharsets.UTF_8);
            if (truncated) {
                text += "\n[output truncated after " + maxBytes + " bytes]";
            }
            if (readFailure != null) {
                text += "\n[output read failed: " + readFailure.getMessage() + "]";
            }
            return text;
        }
    }
}
