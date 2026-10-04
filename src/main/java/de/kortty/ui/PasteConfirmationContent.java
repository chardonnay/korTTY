package de.kortty.ui;

import de.kortty.paste.PasteConfirmationRequest;
import de.kortty.paste.PasteInspection;
import de.kortty.paste.PasteReason;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The texts of a {@link PasteConfirmationDialog}, worked out from the request without JavaFX, so a
 * test can check what the user is told.
 *
 * @param title the window title
 * @param header the question: paste this text into which pane
 * @param summary the line count and the size
 * @param reasons one sentence per reason the paste asks, in the order of {@link PasteReason}
 * @param notes what else the user should know: bracketed paste, where the text comes from, removed
 *     markers, broadcast mode
 * @param previewLabel the label above the preview
 * @param previewText the start of the text, with hidden characters made visible
 * @param previewLegend how hidden characters are shown, or null when the text has none
 * @param previewTruncated that the preview is shortened, or null when it shows the whole text
 * @param settingsHint where paste protection is configured: Settings → Terminal, or the connection's
 *     settings when the pane's connection sets its own paste warning and that warning asks (and both
 *     when the paste is also too large, as the size check is always in Settings → Terminal)
 * @param pasteButton the label of the button that pastes
 * @param cancelButton the label of the default button, which drops the paste
 */
record PasteConfirmationContent(String title, String header, String summary, List<String> reasons,
        List<String> notes, String previewLabel, String previewText, String previewLegend, String previewTruncated,
        String settingsHint, String pasteButton, String cancelButton) {

    /** Looks up a translated text; {@code {0}}, {@code {1}} in it are replaced by {@code args}. */
    @FunctionalInterface
    interface Translator {
        String get(String key, Object... args);
    }

    static final String KEY_PREFIX = "terminal.paste.confirm.";

    private static final long KIB = 1024;

    private static final long MIB = 1024 * 1024;

    PasteConfirmationContent {
        reasons = List.copyOf(reasons);
        notes = List.copyOf(notes);
    }

    /**
     * The texts for {@code request}.
     *
     * @param request what the guard asks about
     * @param inspection the measured text of {@code request}
     * @param translator the translations to use
     * @param locale the locale sizes are formatted in
     */
    static PasteConfirmationContent of(PasteConfirmationRequest request, PasteInspection inspection,
            Translator translator, Locale locale) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(inspection, "inspection");
        Objects.requireNonNull(translator, "translator");
        Locale numbers = locale != null ? locale : Locale.ROOT;
        String size = formatSize(inspection.utf8Bytes(), translator, numbers);

        String header = request.label().isBlank()
            ? text(translator, "header.unnamed")
            : text(translator, "header", request.label());
        String summary = text(translator, "summary", inspection.lineCount(), size);

        List<String> reasons = new ArrayList<>();
        for (PasteReason reason : request.reasons()) {
            switch (reason) {
                case MULTI_LINE -> reasons.add(inspection.lineCount() == 1 && inspection.endsWithLineBreak()
                    ? text(translator, "reason.trailingLineBreak")
                    : text(translator, "reason.multiLine"));
                case CONTROL_CHARACTERS -> {
                    if (inspection.controlCharCount() > 0) {
                        reasons.add(text(translator, "reason.controlCharacters", inspection.controlCharCount()));
                    }
                    if (inspection.bidiControlCount() > 0) {
                        reasons.add(text(translator, "reason.bidiCharacters", inspection.bidiControlCount()));
                    }
                }
                case LARGE -> reasons.add(text(translator, "reason.large", size));
            }
        }

        List<String> notes = new ArrayList<>();
        notes.add(text(translator, request.bracketed() ? "bracketed" : "notBracketed"));
        switch (request.source()) {
            case SELECTION -> notes.add(text(translator, "source.selection"));
            case DROP -> notes.add(text(translator, "source.drop"));
            case AI -> notes.add(text(translator, "source.ai"));
            case CLIPBOARD -> {
                // The usual case; nothing to point out.
            }
        }
        if (inspection.containsBracketMarker()) {
            notes.add(text(translator, "markersRemoved"));
        }
        if (request.broadcastActive()) {
            notes.add(text(translator, "broadcast"));
        }

        PasteInspection.Preview preview = inspection.preview();
        boolean showsHiddenCharacters = inspection.containsControlCharacters() || preview.text().contains("<U+");
        return new PasteConfirmationContent(
            text(translator, "title"),
            header,
            summary,
            reasons,
            notes,
            text(translator, "preview"),
            preview.text(),
            showsHiddenCharacters ? text(translator, "previewLegend") : null,
            preview.truncated() ? text(translator, "previewTruncated") : null,
            settingsHint(request, translator),
            text(translator, "paste"),
            translator.get("dialog.cancel"));
    }

    /**
     * Where the user changes what made this paste ask. The connection editor only when the pane's
     * connection sets its own warning and that warning asks (line breaks or control characters); the
     * size check always comes from Settings → Terminal, so a paste that asks only for its size points
     * there, and one that asks for both names both places.
     */
    private static String settingsHint(PasteConfirmationRequest request, Translator translator) {
        Set<PasteReason> reasons = request.reasons();
        boolean connectionWarningAsks = request.setByConnection()
            && (reasons.contains(PasteReason.MULTI_LINE) || reasons.contains(PasteReason.CONTROL_CHARACTERS));
        if (!connectionWarningAsks) {
            return text(translator, "settingsHint");
        }
        String connection = text(translator, "settingsHint.connection");
        return reasons.contains(PasteReason.LARGE)
            ? connection + " " + text(translator, "settingsHint.size")
            : connection;
    }

    /** What a screen reader announces for the preview: the question and the reasons. */
    String accessibleText() {
        StringBuilder text = new StringBuilder(header);
        for (String reason : reasons) {
            text.append(' ').append(reason);
        }
        return text.toString();
    }

    /**
     * {@code bytes} as bytes below 1 KiB, else as KiB below 1 MiB, else as MiB, with one decimal in
     * the locale's format.
     */
    static String formatSize(long bytes, Translator translator, Locale locale) {
        if (bytes < KIB) {
            return text(translator, "size.bytes", bytes);
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale != null ? locale : Locale.ROOT);
        format.setMaximumFractionDigits(1);
        format.setMinimumFractionDigits(1);
        if (bytes < MIB) {
            return text(translator, "size.kib", format.format(bytes / (double) KIB));
        }
        return text(translator, "size.mib", format.format(bytes / (double) MIB));
    }

    private static String text(Translator translator, String key, Object... args) {
        return translator.get(KEY_PREFIX + key, args);
    }
}
