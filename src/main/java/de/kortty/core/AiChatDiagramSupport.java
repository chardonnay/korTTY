package de.kortty.core;

import java.util.Locale;

/**
 * Detects diagram code blocks in AI chat responses (PlantUML, Mermaid) and normalizes their
 * sources for rendering.
 */
public final class AiChatDiagramSupport {

    private AiChatDiagramSupport() {
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
     * renderer requires; AI responses sometimes omit it inside a ```plantuml fence.
     */
    public static String normalizePlantUml(String content) {
        String source = content != null ? content.strip() : "";
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
