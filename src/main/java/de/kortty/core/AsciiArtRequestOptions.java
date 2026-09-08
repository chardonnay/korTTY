package de.kortty.core;

import de.kortty.model.AsciiArtPictureSize;

/**
 * Per-request options for {@link AiAction#GENERATE_ASCII_ART}, carried on {@link AiRequest} the same
 * way {@code diagramType} is carried for Mermaid generation.
 *
 * <p>{@link Mode#SVG} asks the model for a restricted SVG drawing that korTTY rasterises and
 * converts to characters itself; {@link Mode#ASCII} is the legacy contract where the model types
 * the characters directly and is only used as the last-resort fallback. {@code size} is the
 * character grid the picture is fitted into (the SVG prompt does not depend on it, the legacy prompt
 * does). {@code repairFeedback} is {@code null} on a first attempt and otherwise the sentence that
 * tells the model why its previous answer could not be used.
 */
public record AsciiArtRequestOptions(Mode mode, AsciiArtPictureSize size, String repairFeedback) {

    /** How the model is asked to deliver the picture. */
    public enum Mode {
        /** A restricted SVG drawing, converted locally. */
        SVG,
        /** The legacy contract: the model types printable ASCII characters directly. */
        ASCII
    }

    public AsciiArtRequestOptions {
        mode = mode != null ? mode : Mode.SVG;
        size = size != null ? size : AsciiArtPictureSize.DEFAULT;
        repairFeedback = repairFeedback != null && !repairFeedback.isBlank() ? repairFeedback.strip() : null;
    }

    /** Options for a first SVG request on {@code size}. */
    public static AsciiArtRequestOptions svg(AsciiArtPictureSize size) {
        return new AsciiArtRequestOptions(Mode.SVG, size, null);
    }

    /** Options for the legacy direct-ASCII fallback on {@code size}. */
    public static AsciiArtRequestOptions ascii(AsciiArtPictureSize size) {
        return new AsciiArtRequestOptions(Mode.ASCII, size, null);
    }

    /** The same options with the repair feedback for a second attempt. */
    public AsciiArtRequestOptions withRepairFeedback(String feedback) {
        return new AsciiArtRequestOptions(mode, size, feedback);
    }

    /** {@code options}, or the default SVG request on the default grid when {@code null}. */
    public static AsciiArtRequestOptions orDefault(AsciiArtRequestOptions options) {
        return options != null ? options : svg(AsciiArtPictureSize.DEFAULT);
    }

    public boolean isSvg() {
        return mode == Mode.SVG;
    }
}
