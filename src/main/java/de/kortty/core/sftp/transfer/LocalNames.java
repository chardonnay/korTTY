package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks names that come from an SFTP server before they become local files.
 *
 * <p>{@link #localChild(Path, String)} refuses every name that would land outside the target folder
 * ({@code ..}, {@code a/b}, an absolute path, {@code a\b} on Windows) and, on Windows, names the
 * system cannot hold or would silently turn into something else: the reserved device names
 * ({@code CON}, {@code PRN}, {@code AUX}, {@code NUL}, {@code COM1}..{@code COM9},
 * {@code LPT1}..{@code LPT9}, with or without an extension), a trailing dot or space, and the
 * characters {@code : < > " | ? *}.
 *
 * <p>On a case-insensitive file store (the macOS and Windows defaults) {@code Report.txt} and
 * {@code report.txt} are the same file; {@link #caseCollision(Path, String)} finds such an entry
 * so the caller can treat it as a conflict instead of overwriting it unawares.
 */
public final class LocalNames {

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS_NAME.contains("win");
    private static final boolean MAC = OS_NAME.contains("mac");

    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final String WINDOWS_FORBIDDEN = ":<>\"|?*";

    private LocalNames() {
    }

    /** Whether this JVM applies the Windows name rules. */
    public static boolean windowsRules() {
        return WINDOWS;
    }

    /** {@code name} inside {@code folder}, with the name rules of this platform. */
    public static Path localChild(Path folder, String name) throws IOException {
        return localChild(folder, name, WINDOWS);
    }

    /**
     * {@code name} inside {@code folder}. Throws when the name is not a single plain entry of that
     * folder, or, with {@code windows}, when Windows reserves or rejects it.
     */
    public static Path localChild(Path folder, String name, boolean windows) throws IOException {
        checkName(name, windows);
        Path child;
        try {
            child = folder.resolve(name);
        } catch (InvalidPathException e) {
            throw new IOException(I18n.get("sftp.error.invalidName", name), e);
        }
        if (!folder.equals(child.getParent()) || child.getFileName() == null
                || !name.equals(child.getFileName().toString())) {
            throw new IOException(I18n.get("sftp.error.invalidName", name));
        }
        return child;
    }

    /** Throws when {@code name} cannot be a local entry name (see the class comment). */
    public static void checkName(String name, boolean windows) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0 || (windows && name.indexOf('\\') >= 0)) {
            throw new IOException(I18n.get("sftp.error.invalidName", String.valueOf(name)));
        }
        if (windows && !isValidOnWindows(name)) {
            throw new IOException(I18n.get("sftp.error.reservedLocalName", name));
        }
    }

    /**
     * Whether Windows can store {@code name} as it is: no reserved device name (also with an
     * extension), no trailing dot or space, no {@code : < > " | ? *} and no control characters.
     */
    public static boolean isValidOnWindows(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char last = name.charAt(name.length() - 1);
        if (last == '.' || last == ' ') {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || WINDOWS_FORBIDDEN.indexOf(c) >= 0) {
                return false;
            }
        }
        int dot = name.indexOf('.');
        String base = (dot < 0 ? name : name.substring(0, dot)).stripTrailing();
        return !RESERVED.contains(base.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether the file store holding {@code folder} ignores case. Probed through the folder or its
     * nearest ancestor whose name has letters; without one the platform default applies (Windows
     * and macOS ignore case).
     */
    public static boolean isCaseInsensitive(Path folder) {
        Path current = folder.toAbsolutePath().normalize();
        while (current != null && current.getFileName() != null) {
            String name = current.getFileName().toString();
            String swapped = swapCase(name);
            if (!swapped.equals(name)) {
                Path variant = current.resolveSibling(swapped);
                try {
                    return Files.exists(variant, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(current, variant);
                } catch (IOException | RuntimeException e) {
                    return WINDOWS || MAC;
                }
            }
            current = current.getParent();
        }
        return WINDOWS || MAC;
    }

    /**
     * The name of an existing entry in {@code folder} that differs from {@code name} only in case,
     * when the folder's file store ignores case; empty otherwise. A write to {@code name} would
     * replace that entry.
     */
    public static Optional<String> caseCollision(Path folder, String name) throws IOException {
        if (name == null || !isCaseInsensitive(folder)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                String existing = entry.getFileName().toString();
                if (!existing.equals(name) && existing.equalsIgnoreCase(name)) {
                    return Optional.of(existing);
                }
            }
        }
        return Optional.empty();
    }

    private static String swapCase(String text) {
        StringBuilder swapped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            swapped.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return swapped.toString();
    }
}
