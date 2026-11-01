package de.kortty.ui;

import de.kortty.memory.external.ExternalMemoryEntry;
import de.kortty.memory.external.ExternalMemoryService;
import de.kortty.memory.external.ExternalMemoryStatus;
import de.kortty.memory.external.ExternalMemoryTool;

/**
 * The texts and colours of the reference memory files: tool names, status badges and the reasons a file is refused.
 * A reason names a line and a kind of secret, never the value.
 */
final class ExternalMemoryTexts {

    static final String STATUS_OK_STYLE =
        "-fx-background-color: rgba(47,133,90,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    static final String STATUS_WARN_STYLE =
        "-fx-background-color: rgba(234,179,8,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    static final String STATUS_OFF_STYLE =
        "-fx-background-color: rgba(128,128,128,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    static final String STATUS_REFUSED_STYLE =
        "-fx-background-color: rgba(197,48,48,0.22); -fx-background-radius: 4; -fx-padding: 1 6 1 6;";
    private static final String SECRET_PREFIX = "SECRET:";

    private ExternalMemoryTexts() {
    }

    /** The name of the tool: its product name, or the localized label of the two shared kinds. */
    static String toolLabel(ExternalMemoryTool tool) {
        return switch (tool) {
            case OTHER -> I18n.get("settings.ai.memory.external.tool.other");
            case AGENTS_MD -> I18n.get("settings.ai.memory.external.tool.agentsMd");
            default -> tool.displayName();
        };
    }

    /** The badge of an entry: {@code off} for a switched-off entry that was last found in order. */
    static String statusText(ExternalMemoryEntry entry) {
        if (!entry.active() && entry.status() == ExternalMemoryStatus.OK) {
            return I18n.get("settings.ai.memory.external.status.inactive");
        }
        return switch (entry.status()) {
            case OK -> I18n.get("settings.ai.memory.external.status.ok");
            case CHANGED -> I18n.get("settings.ai.memory.external.status.changed");
            case MISSING -> I18n.get("settings.ai.memory.external.status.missing");
            case REFUSED_SECRET -> I18n.get("settings.ai.memory.external.status.refusedSecret");
            case REFUSED_PATH -> I18n.get("settings.ai.memory.external.status.refusedPath");
        };
    }

    static String statusStyle(ExternalMemoryEntry entry) {
        if (!entry.active() && entry.status() == ExternalMemoryStatus.OK) {
            return STATUS_OFF_STYLE;
        }
        return switch (entry.status()) {
            case OK -> STATUS_OK_STYLE;
            case CHANGED, MISSING -> STATUS_WARN_STYLE;
            case REFUSED_SECRET, REFUSED_PATH -> STATUS_REFUSED_STYLE;
        };
    }

    /** Why a file is not sent, in words; empty when the entry has no reason to show. */
    static String reasonText(ExternalMemoryEntry entry) {
        String reason = entry.reason();
        if (reason.isEmpty() || entry.status() == ExternalMemoryStatus.OK) {
            return "";
        }
        if (reason.startsWith(SECRET_PREFIX)) {
            String[] parts = reason.substring(SECRET_PREFIX.length()).split(":", 2);
            if (parts.length == 2) {
                try {
                    return I18n.get("settings.ai.memory.secret.refused", Integer.parseInt(parts[0]),
                        AiMemoryMessages.categoryLabel(de.kortty.core.AiSecretGuard.Category.valueOf(parts[1])));
                } catch (IllegalArgumentException malformed) {
                    return I18n.get("settings.ai.memory.external.reason.secretUnknown");
                }
            }
        }
        return switch (reason) {
            case "OUTSIDE_ROOT" -> I18n.get("settings.ai.memory.external.reason.outside");
            case "NETWORK_FILE_SYSTEM" -> I18n.get("settings.ai.memory.external.reason.network");
            case "POLICY" -> I18n.get("settings.ai.memory.external.reason.policy");
            case "NOT_MARKDOWN" -> I18n.get("settings.ai.memory.external.reason.notMarkdown");
            case "NOT_FOUND" -> I18n.get("settings.ai.memory.external.reason.notFound");
            case "TOO_LARGE" -> I18n.get("settings.ai.memory.external.reason.tooLarge");
            case "BINARY" -> I18n.get("settings.ai.memory.external.reason.binary");
            case "UNREADABLE" -> I18n.get("settings.ai.memory.external.reason.unreadable");
            default -> I18n.get("settings.ai.memory.external.reason.changed");
        };
    }

    /** Why a file cannot be approved, in words. */
    static String problemText(ExternalMemoryService.Problem problem) {
        return switch (problem) {
            case NOT_FOUND -> I18n.get("settings.ai.memory.external.reason.notFound");
            case NOT_MARKDOWN -> I18n.get("settings.ai.memory.external.reason.notMarkdown");
            case OUTSIDE_ROOT -> I18n.get("settings.ai.memory.external.reason.outside");
            case NETWORK_FILE_SYSTEM -> I18n.get("settings.ai.memory.external.reason.network");
            case POLICY -> I18n.get("settings.ai.memory.external.reason.policy");
            case TOO_LARGE -> I18n.get("settings.ai.memory.external.reason.tooLarge");
            case BINARY -> I18n.get("settings.ai.memory.external.reason.binary");
            case SECRET -> I18n.get("settings.ai.memory.external.reason.secretUnknown");
            case CHANGED -> I18n.get("settings.ai.memory.external.reason.changedReview");
            case DUPLICATE -> I18n.get("settings.ai.memory.external.reason.duplicate");
        };
    }
}
