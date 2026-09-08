package de.kortty.core;

import de.kortty.model.AsciiArtPictureSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Helpers for the ASCII Art tool: preview zoom, the AI "draw this subject" pipeline, and turning a
 * legacy typed reply into a picture that is safe to show in a monospace preview.
 *
 * <p>Deliberately free of JavaFX so the clamping, sanitizing and orchestration rules stay
 * unit-testable. The system and user prompts live in {@link AiPromptBuilder} with every other
 * action's prompt text; only the per-retry variation instruction is built here, because it travels
 * as the request's {@code userPrompt} the same way a dialog's instruction field would.
 *
 * <p>The pipeline asks the model for a restricted SVG drawing and converts it locally through
 * {@link AsciiArtPictureRenderer}: a language model describes shapes far more reliably than it
 * types aligned characters, and a local conversion is deterministic. An unusable answer gets exactly
 * one repair request that names the defect; only when that fails too, and no drawable candidate
 * exists at all, does the legacy contract — the model types the characters — run as the last
 * resort, so nothing is worse than before the SVG pipeline existed. A reply that was cut off before
 * any shape arrived is not retried: a completion limit recurs deterministically, and the cause (a
 * thinking model spending the budget on hidden reasoning) is something only the user can change.
 */
public final class AsciiArtSupport {

    private static final Logger logger = LoggerFactory.getLogger(AsciiArtSupport.class);

    /** Preview font size bounds in px. The dialog's zoom buttons step inside this range. */
    public static final double MIN_PREVIEW_FONT_SIZE = 6.0;
    public static final double MAX_PREVIEW_FONT_SIZE = 40.0;
    public static final double DEFAULT_PREVIEW_FONT_SIZE = 12.0;

    /** One zoom click. */
    private static final double PREVIEW_FONT_SIZE_STEP = 1.0;

    /** Upper bound on the subject text sent to the model, so a pasted wall of text cannot become the prompt. */
    private static final int MAX_SUBJECT_LENGTH = 200;

    /**
     * Treatments rotated through on each regeneration. The AI layer exposes no temperature or seed,
     * so a visibly different variant has to be asked for in words — in composition terms, because
     * the model draws shapes rather than typing characters.
     */
    private static final List<String> VARIATION_HINTS = List.of(
        "Show the subject from a different viewpoint than the most obvious one: from the side, from slightly above, or from behind.",
        "Draw a minimal version: at most 8 shapes, one big silhouette plus only the details that identify the subject.",
        "Draw a detailed version: 20 to 30 shapes, adding secondary details such as windows, texture bands, or small objects nearby.",
        "Place the subject in a wider scene with a horizon, sky elements, ground, and at least two surrounding objects.",
        "Draw a stylised, geometric version built only from rectangles, circles, and triangles.",
        "Change the proportions: make the subject noticeably taller and narrower, or wider and flatter, than usual.",
        "Use strong contrast: black silhouettes on an empty background with almost no grey.",
        "Use soft tones: mostly #aaa and #555 fills with black only for a few accents.");

    private AsciiArtSupport() {
    }

    // ---- Preview zoom ----

    /** Clamps {@code size} into the supported preview range; NaN and 0 fall back to the default. */
    public static double clampPreviewFontSize(double size) {
        if (Double.isNaN(size) || size <= 0) {
            return DEFAULT_PREVIEW_FONT_SIZE;
        }
        return Math.min(MAX_PREVIEW_FONT_SIZE, Math.max(MIN_PREVIEW_FONT_SIZE, size));
    }

    /** Moves the preview font size by {@code steps} zoom clicks (negative shrinks), staying in range. */
    public static double stepPreviewFontSize(double current, int steps) {
        return clampPreviewFontSize(clampPreviewFontSize(current) + steps * PREVIEW_FONT_SIZE_STEP);
    }

    /** The inline style for a preview area at {@code fontSize}. */
    public static String previewStyle(double fontSize) {
        return String.format(Locale.ROOT,
            "-fx-font-family: monospace; -fx-font-size: %.1fpx;", clampPreviewFontSize(fontSize));
    }

    /** The zoom level as a percentage of the default size, for the label between the zoom buttons. */
    public static int zoomPercent(double fontSize) {
        return (int) Math.round(clampPreviewFontSize(fontSize) / DEFAULT_PREVIEW_FONT_SIZE * 100.0);
    }

    // ---- AI generation ----

    /** The step the pipeline is in, for the dialog's status line. */
    public enum Stage {
        /** Waiting for the model's drawing. */
        REQUESTING,
        /** Rasterising the drawing and choosing characters. */
        CONVERTING,
        /** The first answer was unusable; waiting for the corrected drawing. */
        REPAIRING,
        /** Both drawings were unusable; waiting for the model to type the picture directly. */
        FALLBACK
    }

    /** Which request produced the picture. */
    public enum Source {
        SVG,
        SVG_REPAIR,
        LEGACY_ASCII
    }

    /**
     * The outcome of one generation. {@code picture} is {@code null} when every attempt failed;
     * {@code rejection} then names the last reason. {@code truncated} marks a picture rendered
     * from an answer the model could not finish (the completion limit or a cut stream), which is
     * shown anyway because a partial drawing is still a picture.
     */
    public record AsciiArtResult(
        String picture,
        int columns,
        int rows,
        Source source,
        boolean truncated,
        boolean streamInterrupted,
        AsciiArtPictureRenderer.RejectReason rejection,
        int requests) {

        public boolean isUsable() {
            return picture != null && !picture.isBlank();
        }
    }

    /** Engine seam: lets the orchestration be tested without rasterising anything. */
    @FunctionalInterface
    interface SvgPictureRenderer {
        AsciiArtPictureRenderer.RenderResult render(String rawReply, int columns, int rows) throws InterruptedException;
    }

    /**
     * The extra instruction for regeneration {@code attempt} (0-based). Returns {@code null} for the
     * first attempt so the model is not steered away from its best default depiction.
     */
    public static String variationInstructions(int attempt) {
        if (attempt <= 0) {
            return null;
        }
        String hint = VARIATION_HINTS.get((attempt - 1) % VARIATION_HINTS.size());
        return "This is attempt " + (attempt + 1) + " for the same subject. "
            + "The picture must look clearly different from the previous attempts: " + hint;
    }

    /**
     * Asks {@code aiService} to draw {@code subject} on a {@code size} grid and returns the outcome,
     * or {@code null} when the subject is blank.
     *
     * <p>Blocking — call it from a background thread; a thread interrupt (the dialog's Cancel)
     * stops the pipeline before the next request or render step. AI skills are switched off for
     * these requests: a user skill about, say, shell scripting only adds noise to a drawing task.
     * {@code usageRecorder} sees every request that was sent, including the repair and fallback
     * ones, so token accounting stays complete. {@code stageListener} is called on the calling
     * thread before each step.
     */
    public static AsciiArtResult generateAsciiArt(
            AiService aiService,
            String subject,
            String connectionDisplayName,
            String responseLanguageCode,
            int attempt,
            AsciiArtPictureSize size,
            BiConsumer<AiRequest, AiExecutionResult> usageRecorder,
            Consumer<Stage> stageListener) throws Exception {

        return generateAsciiArt(aiService, subject, connectionDisplayName, responseLanguageCode, attempt, size,
            usageRecorder, stageListener,
            (reply, columns, rows) -> AsciiArtPictureRenderer.renderReply(
                reply, columns, rows, AiActionSkillPromptSupport.asciiArtExampleSvg()));
    }

    static AsciiArtResult generateAsciiArt(
            AiService aiService,
            String subject,
            String connectionDisplayName,
            String responseLanguageCode,
            int attempt,
            AsciiArtPictureSize size,
            BiConsumer<AiRequest, AiExecutionResult> usageRecorder,
            Consumer<Stage> stageListener,
            SvgPictureRenderer renderer) throws Exception {

        if (aiService == null) {
            throw new IllegalStateException("No AI service is available for ASCII art generation.");
        }
        String trimmedSubject = subject != null ? subject.trim() : "";
        if (trimmedSubject.isEmpty()) {
            return null;
        }
        if (trimmedSubject.length() > MAX_SUBJECT_LENGTH) {
            trimmedSubject = trimmedSubject.substring(0, MAX_SUBJECT_LENGTH).trim();
        }
        AsciiArtPictureSize grid = size != null ? size : AsciiArtPictureSize.DEFAULT;
        Run run = new Run(aiService, grid, usageRecorder, stageListener, renderer);

        AiRequest first = new AiRequest(
            AiAction.GENERATE_ASCII_ART,
            trimmedSubject,
            connectionDisplayName,
            responseLanguageCode,
            variationInstructions(attempt),
            null,
            false).withAsciiArtOptions(AsciiArtRequestOptions.svg(grid));

        // 1. The drawing.
        run.notify(Stage.REQUESTING);
        Attempt drawing = run.svgAttempt(first, "no-drawable-svg");
        if (drawing.accepted()) {
            return run.success(drawing.picture(), Source.SVG, drawing.reply());
        }
        if (drawing.truncatedEmpty()) {
            return run.failure(Source.SVG, drawing.reply(), AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY);
        }
        Candidate best = drawing.candidate(Source.SVG);

        // 2. Exactly one repair that names the defect.
        run.notify(Stage.REPAIRING);
        AiRequest repair = first.withAsciiArtOptions(
            first.asciiArtOptions().withRepairFeedback(repairFeedback(drawing)));
        Attempt repaired = run.svgAttempt(repair, "unusable-svg-repair");
        if (repaired.accepted()) {
            return run.success(repaired.picture(), Source.SVG_REPAIR, repaired.reply());
        }
        best = Candidate.better(best, repaired.candidate(Source.SVG_REPAIR));
        if (best != null) {
            // An imperfect drawing of the subject beats a third request that produces the same
            // class of picture the user already rejected once.
            return run.success(best.picture(), best.source(), best.reply());
        }
        if (repaired.truncatedEmpty()) {
            return run.failure(Source.SVG_REPAIR, repaired.reply(), AsciiArtPictureRenderer.RejectReason.TRUNCATED_EMPTY);
        }

        // 3. Last resort: the legacy contract where the model types the characters itself.
        run.notify(Stage.FALLBACK);
        AiRequest legacy = first.withAsciiArtOptions(AsciiArtRequestOptions.ascii(grid));
        AiExecutionResult reply = run.execute(legacy);
        String picture = fitToGrid(extractAsciiArt(reply != null ? reply.content() : null), grid);
        if (picture != null) {
            return run.success(picture, Source.LEGACY_ASCII, reply);
        }
        run.archive("no-usable-ascii", reply, "the typed reply held no picture", false);
        return run.failure(Source.LEGACY_ASCII, reply, repaired.rejection());
    }

    /**
     * The sentence sent back to the model for the repair round: the renderer's feedback, which
     * names the ignored elements or the broken path data where it can, or the plain hint.
     */
    private static String repairFeedback(Attempt attempt) {
        String hint = attempt.rendered().repairFeedback();
        if (hint == null && attempt.rejection() != null) {
            hint = attempt.rejection().repairHint();
        }
        StringBuilder feedback = new StringBuilder(hint != null ? hint : "It could not be converted into a picture.");
        // A cut stream is transient and says nothing about the answer's length; only the
        // completion limit does.
        if (attempt.reply() != null && attempt.reply().outputTruncated() && !attempt.reply().streamInterrupted()) {
            feedback.append(" The answer was also cut off, so keep the drawing short: at most 25 shapes.");
        }
        return feedback.toString();
    }

    /** One generation's shared state: the service, the grid, the recorder, the listeners and the request count. */
    private static final class Run {
        private final AiService aiService;
        private final AsciiArtPictureSize grid;
        private final BiConsumer<AiRequest, AiExecutionResult> usageRecorder;
        private final Consumer<Stage> stageListener;
        private final SvgPictureRenderer renderer;
        private int requestCount;

        Run(AiService aiService,
            AsciiArtPictureSize grid,
            BiConsumer<AiRequest, AiExecutionResult> usageRecorder,
            Consumer<Stage> stageListener,
            SvgPictureRenderer renderer) {
            this.aiService = aiService;
            this.grid = grid;
            this.usageRecorder = usageRecorder;
            this.stageListener = stageListener;
            this.renderer = renderer;
        }

        void notify(Stage stage) {
            if (stageListener != null) {
                stageListener.accept(stage);
            }
        }

        /**
         * Sends one request and records its usage. An empty reply from an OpenAI-compatible endpoint
         * is not a transport failure here — it is the model answering with nothing (or with reasoning
         * only), which the pipeline treats as an unusable answer worth one repair — so it is turned
         * into an empty result carrying whatever the transport could parse.
         */
        AiExecutionResult execute(AiRequest request) throws Exception {
            requestCount++;
            AiExecutionResult result;
            try {
                result = aiService.execute(request);
            } catch (OpenAiCompatibleAiService.EmptyResponseException e) {
                AiExecutionResult partial = e.partialResult();
                result = partial != null
                    ? new AiExecutionResult("", partial.usage(), partial.reasoning(),
                        partial.outputTruncated(), partial.streamInterrupted())
                    : new AiExecutionResult("", null);
            }
            if (result != null && usageRecorder != null) {
                usageRecorder.accept(request, result);
            }
            checkInterrupted();
            return result;
        }

        Attempt svgAttempt(AiRequest request, String archiveLabel) throws Exception {
            AiExecutionResult reply = execute(request);
            String content = reply != null ? reply.content() : null;
            notify(Stage.CONVERTING);
            AsciiArtPictureRenderer.RenderResult rendered =
                renderer.render(content != null ? content : "", grid.columns(), grid.rows());
            checkInterrupted();
            Attempt attempt = new Attempt(reply, rendered);
            if (!attempt.accepted()) {
                archive(archiveLabel, reply, AsciiArtPictureRenderer.describe(rendered), rendered.isSoftRejection());
            }
            return attempt;
        }

        void archive(String label, AiExecutionResult reply, String reason, boolean soft) {
            String content = reply != null ? reply.content() : null;
            Path archived = AiAnswerArchive.save(AiAction.GENERATE_ASCII_ART, label, content);
            logger.warn("ASCII art answer rejected: {} [soft={}, chars={}, truncated={}, interrupted={}, grid={}x{}] {}",
                reason,
                soft,
                content != null ? content.length() : 0,
                reply != null && reply.outputTruncated(),
                reply != null && reply.streamInterrupted(),
                grid.columns(), grid.rows(),
                archived != null ? "Full answer archived at " + archived : "Full answer not archived (archive off)");
        }

        AsciiArtResult success(String picture, Source source, AiExecutionResult reply) {
            logger.info("ASCII art picture produced via {} after {} request(s) [grid={}x{}, truncated={}]",
                source, requestCount, grid.columns(), grid.rows(), reply != null && reply.outputTruncated());
            return new AsciiArtResult(picture, grid.columns(), grid.rows(), source,
                reply != null && reply.outputTruncated(), reply != null && reply.streamInterrupted(),
                null, requestCount);
        }

        AsciiArtResult failure(Source source, AiExecutionResult reply, AsciiArtPictureRenderer.RejectReason rejection) {
            return new AsciiArtResult(null, grid.columns(), grid.rows(), source,
                reply != null && reply.outputTruncated(), reply != null && reply.streamInterrupted(),
                rejection, requestCount);
        }
    }

    /** One SVG answer after conversion. */
    private record Attempt(AiExecutionResult reply, AsciiArtPictureRenderer.RenderResult rendered) {

        boolean accepted() {
            return rendered.isAccepted() && rendered.text() != null && !rendered.text().isBlank();
        }

        String picture() {
            return rendered.text();
        }

        AsciiArtPictureRenderer.RejectReason rejection() {
            return rendered.rejection();
        }

        /**
         * True when the answer hit the completion limit and nothing drawable arrived: the budget
         * went to hidden reasoning, which a second request would repeat exactly. A cut stream also
         * counts as truncated but is transient, so it keeps the ordinary repair round.
         */
        boolean truncatedEmpty() {
            if (reply == null || !reply.outputTruncated() || reply.streamInterrupted() || accepted()) {
                return false;
            }
            AsciiArtPictureRenderer.RejectReason reason = rendered.rejection();
            return reason == AsciiArtPictureRenderer.RejectReason.NO_SVG
                || reason == AsciiArtPictureRenderer.RejectReason.NO_SHAPES;
        }

        /** The picture of a soft rejection, kept as the best drawable candidate so far. */
        Candidate candidate(Source source) {
            if (!rendered.isSoftRejection() || rendered.text() == null || rendered.text().isBlank()) {
                return null;
            }
            double coverage = rendered.stats() != null ? rendered.stats().boundsCoverage() : 0.0;
            int shapes = rendered.stats() != null ? rendered.stats().shapes() : 0;
            return new Candidate(rendered.text(), source, reply, coverage, shapes);
        }
    }

    /** A drawable but imperfect picture, ranked by how much of the canvas it fills. */
    private record Candidate(String picture, Source source, AiExecutionResult reply, double coverage, int shapes) {

        static Candidate better(Candidate a, Candidate b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            if (b.coverage > a.coverage + 1e-9) {
                return b;
            }
            if (Math.abs(b.coverage - a.coverage) <= 1e-9 && b.shapes > a.shapes) {
                return b;
            }
            return a;
        }
    }

    /** Mirrors the apply flow's cancellation rule: a cancelled dialog must not spend another request. */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("ASCII art generation was cancelled.");
        }
    }

    // ---- Legacy typed reply ----

    /**
     * Pulls the picture out of a model reply: drops reasoning blocks, prefers the first fenced code
     * block, expands tabs (they break monospace alignment), removes control characters, and trims
     * blank edge lines. Returns {@code null} when nothing usable is left.
     */
    public static String extractAsciiArt(String rawReply) {
        if (rawReply == null || rawReply.isBlank()) {
            return null;
        }
        String text = stripThinkBlocks(rawReply).replace("\r\n", "\n").replace('\r', '\n');
        String fenced = firstFencedBlock(text);
        String body = fenced != null ? fenced : text;
        body = trimBlankEdgeLines(stripControlCharacters(body.replace("\t", "    ")));
        return body.isBlank() ? null : body;
    }

    /**
     * Crops a typed picture to the grid: lines beyond {@code rows} and characters beyond
     * {@code columns} are dropped, then blank edges are trimmed again. Returns {@code null} for a
     * picture that is blank after cropping.
     */
    static String fitToGrid(String picture, AsciiArtPictureSize size) {
        if (picture == null || picture.isBlank()) {
            return null;
        }
        AsciiArtPictureSize grid = size != null ? size : AsciiArtPictureSize.DEFAULT;
        List<String> lines = new ArrayList<>();
        for (String line : picture.split("\n", -1)) {
            if (lines.size() >= grid.rows()) {
                break;
            }
            lines.add(line.length() > grid.columns() ? line.substring(0, grid.columns()) : line);
        }
        String fitted = trimBlankEdgeLines(String.join("\n", lines));
        return fitted.isBlank() ? null : fitted;
    }

    /** Removes {@code <think>…</think>} reasoning some local models inline into the content. */
    private static String stripThinkBlocks(String text) {
        return text.replaceAll("(?is)<think>.*?</think>", "");
    }

    /**
     * The body of the first fenced code block, or {@code null} when the reply is not fenced. Anything
     * on the opening fence line after the backticks is a language tag and is dropped with it.
     */
    private static String firstFencedBlock(String text) {
        int open = text.indexOf("```");
        if (open < 0) {
            return null;
        }
        int bodyStart = text.indexOf('\n', open);
        if (bodyStart < 0) {
            return null;
        }
        int close = text.indexOf("```", bodyStart + 1);
        return close >= 0 ? text.substring(bodyStart + 1, close) : text.substring(bodyStart + 1);
    }

    /** Keeps newlines and printable characters; drops the control characters that would corrupt the preview. */
    private static String stripControlCharacters(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c >= 0x20 && c != 0x7F)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Strips trailing spaces per line and removes leading and trailing blank lines. */
    static String trimBlankEdgeLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, lines.get(i).stripTrailing());
        }
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines);
    }
}
