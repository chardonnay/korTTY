package de.kortty.core;

import de.kortty.model.SnippetDiagram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Local helpers for persisted snippet diagrams.
 */
public final class SnippetDiagramSupport {

    private SnippetDiagramSupport() {
    }

    public static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    public static boolean isStale(SnippetDiagram diagram, String currentContent) {
        if (diagram == null) {
            return false;
        }
        String savedHash = diagram.getSourceContentSha256();
        return savedHash != null && !savedHash.isBlank() && !savedHash.equals(contentHash(currentContent));
    }

    public static String normalizePlantUml(String source) {
        String value = source != null ? source.trim() : "";
        if (value.isBlank()) {
            return "";
        }
        value = value.replaceAll("(?s)^```(?:plantuml|puml)?\\s*", "")
            .replaceAll("(?s)```\\s*$", "")
            .trim();
        if (!value.startsWith("@startuml")) {
            value = "@startuml\n" + value;
        }
        if (!value.endsWith("@enduml")) {
            value = value + "\n@enduml";
        }
        return value.trim();
    }

    public static boolean isRenderablePlantUml(String source) {
        String value = source != null ? source.trim() : "";
        return value.startsWith("@startuml") && value.endsWith("@enduml");
    }

    public static String buildFallbackLogicalStructurePlantUml(String content, String snippetLanguage) {
        String normalizedContent = content != null ? content : "";
        String lowerContent = normalizedContent.toLowerCase(Locale.ROOT);
        List<String> actions = new ArrayList<>();

        if (hasAssignments(normalizedContent)) {
            actions.add("Read configured values");
        }
        actions.add("Run main snippet logic");

        StringBuilder builder = new StringBuilder();
        builder.append("@startuml\n");
        builder.append("start\n");
        for (String action : actions) {
            builder.append(":").append(safeActivityLabel(action)).append(";\n");
        }
        if (hasConditionalFlow(lowerContent)) {
            builder.append("if (Main command succeeds?) then (yes)\n");
            builder.append("  :").append(safeActivityLabel(successAction(lowerContent))).append(";\n");
            builder.append("else (no)\n");
            builder.append("  :").append(safeActivityLabel(failureAction(lowerContent))).append(";\n");
            builder.append("endif\n");
        }
        builder.append("stop\n");
        builder.append("@enduml");
        return builder.toString();
    }

    private static boolean hasAssignments(String content) {
        return content != null && content.lines()
            .anyMatch(line -> line.matches("\\s*[A-Za-z_][A-Za-z0-9_]*=.*"));
    }

    private static boolean hasConditionalFlow(String lowerContent) {
        return lowerContent != null
            && (lowerContent.contains("\nif ")
            || lowerContent.startsWith("if ")
            || lowerContent.contains("\ncase ")
            || lowerContent.contains(" else"));
    }

    private static String successAction(String lowerContent) {
        return lowerContent != null && lowerContent.contains("mail")
            ? "Send success notification"
            : "Handle success path";
    }

    private static String failureAction(String lowerContent) {
        return lowerContent != null && lowerContent.contains("mail")
            ? "Send failure notification"
            : "Handle failure path";
    }

    private static String safeActivityLabel(String label) {
        String value = label != null ? label : "Run step";
        value = value.replace('\n', ' ').replace('\r', ' ');
        value = value.replace(':', '-').replace(';', ',');
        return value.isBlank() ? "Run step" : value.trim();
    }
}
