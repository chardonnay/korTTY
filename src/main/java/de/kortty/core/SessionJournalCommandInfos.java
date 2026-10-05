package de.kortty.core;

import de.kortty.model.SessionJournalCommandInfo;
import de.kortty.model.Snippet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Turns the commands an AI summary mentions into the stored tooltip data, and matches them
 * against the snippet manager: a script the user keeps there is labelled as such, whatever the
 * model believed about it.
 */
final class SessionJournalCommandInfos {

    private SessionJournalCommandInfos() {
    }

    static List<SessionJournalCommandInfo> from(
            List<SessionJournalAiSupport.CommandMention> mentions,
            Function<String, String> snippetLookup) {
        List<SessionJournalCommandInfo> result = new ArrayList<>();
        if (mentions == null) {
            return result;
        }
        for (SessionJournalAiSupport.CommandMention mention : mentions) {
            String snippetName = null;
            if (snippetLookup != null) {
                try {
                    snippetName = snippetLookup.apply(mention.name());
                } catch (RuntimeException ignored) {
                    // the lookup is a nicety; a failing snippet store never costs the entry
                }
            }
            SessionJournalCommandInfo.Origin origin = snippetName != null
                ? SessionJournalCommandInfo.Origin.SNIPPET
                : mention.known() ? SessionJournalCommandInfo.Origin.DISTRIBUTION
                : SessionJournalCommandInfo.Origin.UNKNOWN;
            result.add(new SessionJournalCommandInfo(mention.name(), mention.description(), origin, snippetName));
        }
        return result;
    }

    /**
     * Name of the snippet that {@code command} runs, or {@code null}. A command matches a snippet
     * by its file name ({@code ./deploy.sh}, {@code /opt/bin/deploy.sh} and {@code deploy.sh} all
     * match {@code deploy.sh}; {@code deploy} matches it too) or by the snippet's own name.
     */
    static String findSnippetName(List<Snippet> snippets, String command) {
        if (snippets == null || command == null || command.isBlank()) {
            return null;
        }
        String base = baseName(command.strip());
        if (base.isEmpty()) {
            return null;
        }
        String lower = base.toLowerCase(Locale.ROOT);
        for (Snippet snippet : snippets) {
            String fileName = snippet.getFileName();
            if (fileName != null) {
                String file = baseName(fileName).toLowerCase(Locale.ROOT);
                if (file.equals(lower) || stripExtension(file).equals(lower)) {
                    return displayName(snippet, fileName);
                }
            }
        }
        for (Snippet snippet : snippets) {
            String name = snippet.getName();
            if (name != null && name.strip().equalsIgnoreCase(base)) {
                return displayName(snippet, name);
            }
        }
        return null;
    }

    private static String displayName(Snippet snippet, String fallback) {
        String name = snippet.getName();
        return name != null && !name.isBlank() ? name.strip() : fallback;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
