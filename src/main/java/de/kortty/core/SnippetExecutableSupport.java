package de.kortty.core;

import de.kortty.model.Snippet;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a snippet is written with the executable bit when it is exported or copied to a
 * server. An explicit {@link Snippet#getExecutable()} wins; otherwise scripts with a known script
 * extension ({@code .sh}, {@code .py}, {@code .pl}, …) or a shebang line are executable.
 */
public final class SnippetExecutableSupport {

    /** Mode of an executable file and of every directory: {@code rwxr-xr-x}. */
    public static final int EXECUTABLE_MODE = 0755;
    /** Mode of a plain file: {@code rw-r--r--}. */
    public static final int REGULAR_MODE = 0644;

    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
        "sh", "bash", "zsh", "ksh", "py", "pl", "rb", "groovy", "ps1");

    private SnippetExecutableSupport() {
    }

    /** The effective flag: the snippet's explicit choice, else {@link #defaultExecutable}. */
    public static boolean isExecutable(Snippet snippet) {
        if (snippet == null) {
            return false;
        }
        if (snippet.getExecutable() != null) {
            return snippet.getExecutable();
        }
        return defaultExecutable(fileNameOf(snippet), snippet.getContent());
    }

    /**
     * The automatic flag: true for a file name with a known script extension, or for content that
     * starts with a shebang. A file name without a known extension is decided by the shebang alone.
     */
    public static boolean defaultExecutable(String fileName, String content) {
        if (startsWithShebang(content)) {
            return true;
        }
        String extension = extensionOf(fileName);
        return extension != null && EXECUTABLE_EXTENSIONS.contains(extension);
    }

    /** The file name a snippet is written under: its explicit file name, else name + language extension. */
    public static String fileNameOf(Snippet snippet) {
        if (snippet == null) {
            return "snippet.txt";
        }
        if (snippet.getFileName() != null && !snippet.getFileName().isBlank()) {
            return sanitizeFileName(snippet.getFileName());
        }
        String language = SnippetLanguageSupport.detectSnippetLanguage(snippet.getLanguage(), snippet.getContent());
        return SnippetLanguageSupport.sanitizeFileName(snippet.getName(), language);
    }

    /** The numeric mode ({@code 0755} / {@code 0644}) for a snippet file. */
    public static int fileMode(Snippet snippet) {
        return isExecutable(snippet) ? EXECUTABLE_MODE : REGULAR_MODE;
    }

    /** The POSIX permission set of {@code mode} (only the {@code rwx} bits are honoured). */
    public static Set<PosixFilePermission> posixPermissions(int mode) {
        StringBuilder text = new StringBuilder(9);
        for (int shift = 6; shift >= 0; shift -= 3) {
            int bits = (mode >> shift) & 7;
            text.append((bits & 4) != 0 ? 'r' : '-')
                .append((bits & 2) != 0 ? 'w' : '-')
                .append((bits & 1) != 0 ? 'x' : '-');
        }
        return PosixFilePermissions.fromString(text.toString());
    }

    /**
     * A file name safe on every platform, keeping its extension: path separators and unusual
     * characters become {@code -}; a blank result becomes {@code snippet.txt}.
     */
    public static String sanitizeFileName(String fileName) {
        String candidate = fileName != null ? fileName.trim() : "";
        candidate = candidate.replace('\\', '-').replace('/', '-');
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]+", "-");
        candidate = candidate.replaceAll("-{2,}", "-");
        candidate = candidate.replaceAll("^[.-]+|[-]+$", "");
        return candidate.isBlank() ? "snippet.txt" : candidate;
    }

    static boolean startsWithShebang(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.startsWith("﻿") ? content.substring(1) : content;
        return trimmed.startsWith("#!");
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot > 0 && dot + 1 < lower.length() ? lower.substring(dot + 1) : null;
    }
}
