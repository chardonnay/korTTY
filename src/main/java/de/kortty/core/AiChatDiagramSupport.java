package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects diagram code blocks in AI chat responses (PlantUML, Mermaid) and normalizes their
 * sources for rendering.
 */
public final class AiChatDiagramSupport {

    private static final Pattern DISPLAY_MATH = Pattern.compile("(?s)\\$\\$(.+?)\\$\\$");

    /**
     * One part of a text section: either plain text ({@code math == null}) or a display-math
     * TeX expression extracted from a {@code $$ ... $$} frame.
     */
    public record MathSegment(String text, String math) {
    }

    private AiChatDiagramSupport() {
    }

    /**
     * Splits a text section into plain-text and {@code $$ ... $$} display-math segments so
     * formulas can be typeset while the surrounding prose stays regular text.
     */
    public static List<MathSegment> splitTextWithDisplayMath(String text) {
        List<MathSegment> segments = new ArrayList<>();
        String safeText = text != null ? text : "";
        Matcher matcher = DISPLAY_MATH.matcher(safeText);
        int lastEnd = 0;
        while (matcher.find()) {
            String math = matcher.group(1).strip();
            if (math.isEmpty()) {
                continue;
            }
            if (matcher.start() > lastEnd) {
                String leading = safeText.substring(lastEnd, matcher.start()).strip();
                if (!leading.isEmpty()) {
                    segments.add(new MathSegment(leading, null));
                }
            }
            segments.add(new MathSegment(null, math));
            lastEnd = matcher.end();
        }
        if (segments.isEmpty()) {
            segments.add(new MathSegment(safeText, null));
            return segments;
        }
        String trailing = safeText.substring(lastEnd).strip();
        if (!trailing.isEmpty()) {
            segments.add(new MathSegment(trailing, null));
        }
        return segments;
    }

    /** True when a fenced code block carries PlantUML source. */
    public static boolean isPlantUmlBlock(String language, String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String tag = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";
        if (tag.equals("plantuml") || tag.equals("puml")) {
            return true;
        }
        // Untagged blocks are still recognizable by the mandatory @startuml marker.
        return tag.isEmpty() && content.strip().startsWith("@startuml");
    }

    /**
     * Ensures the PlantUML source carries the {@code @startuml}/{@code @enduml} frame the
     * renderer requires; AI responses sometimes omit it (entirely, or just the truncated end
     * marker) inside a ```plantuml fence. Other {@code @start...} dialects pass through
     * untouched, since their end markers differ per dialect.
     */
    public static String normalizePlantUml(String content) {
        String source = content != null ? content.strip() : "";
        if (source.startsWith("@startuml")) {
            return source.endsWith("@enduml") ? source : source + "\n@enduml";
        }
        if (source.startsWith("@start")) {
            return source;
        }
        return "@startuml\n" + source + "\n@enduml";
    }

    /** True when a fenced code block carries Mermaid source. */
    public static boolean isMermaidBlock(String language) {
        String tag = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";
        return tag.equals("mermaid");
    }

    /**
     * True when a fenced code block carries LaTeX math that MathJax can typeset. Full LaTeX
     * documents (\documentclass/\begin{document}) are left as code.
     */
    public static boolean isLatexMathBlock(String language, String content) {
        String tag = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";
        if (!tag.equals("latex") && !tag.equals("tex") && !tag.equals("math") && !tag.equals("katex")) {
            return false;
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        return !content.contains("\\documentclass") && !content.contains("\\begin{document}");
    }

    /**
     * Strips a surrounding {@code $$ ... $$} or {@code \[ ... \]} display-math frame so the
     * remaining TeX can be handed to the typesetter directly.
     */
    public static String normalizeLatexMath(String content) {
        String source = content != null ? content.strip() : "";
        if (source.startsWith("$$") && source.endsWith("$$") && source.length() > 4) {
            return source.substring(2, source.length() - 2).strip();
        }
        if (source.startsWith("\\[") && source.endsWith("\\]") && source.length() > 4) {
            return source.substring(2, source.length() - 2).strip();
        }
        return source;
    }
}
