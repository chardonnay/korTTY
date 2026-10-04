package de.kortty.core.remote.extract;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** The archive formats korTTY can unpack on a server, recognised by their file name. */
public enum ArchiveKind {
    ZIP("unzip", "", ".zip"),
    TAR("tar", "", ".tar"),
    TAR_GZ("tar", "-z", ".tar.gz", ".tgz"),
    TAR_BZ2("tar", "-j", ".tar.bz2", ".tbz2", ".tbz"),
    TAR_XZ("tar", "-J", ".tar.xz", ".txz"),
    SEVEN_ZIP("7z", "", ".7z");

    private final String tool;
    private final String tarCompressionFlag;
    private final String[] extensions;

    ArchiveKind(String tool, String tarCompressionFlag, String... extensions) {
        this.tool = tool;
        this.tarCompressionFlag = tarCompressionFlag;
        this.extensions = extensions;
    }

    /** The program the server needs; for {@link #SEVEN_ZIP} {@code 7za} is accepted as well. */
    public String tool() {
        return tool;
    }

    /** Whether the archive is read with {@code tar}. */
    public boolean isTar() {
        return "tar".equals(tool);
    }

    /** tar's decompression option ({@code -z}, {@code -j}, {@code -J}) or empty for a plain tar. */
    String tarCompressionFlag() {
        return tarCompressionFlag;
    }

    /** The format of a file name, or empty when korTTY cannot unpack it. Case does not matter. */
    public static Optional<ArchiveKind> detect(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        String lower = fileName.toLowerCase(Locale.ROOT);
        ArchiveKind best = null;
        int bestLength = 0;
        for (ArchiveKind kind : values()) {
            for (String extension : kind.extensions) {
                // the longest match wins, so "x.tar.gz" is TAR_GZ
                if (lower.endsWith(extension) && lower.length() > extension.length() && extension.length() > bestLength) {
                    best = kind;
                    bestLength = extension.length();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The file name without the archive extension, used as the name of the new folder; "extracted"
     * when nothing usable is left.
     */
    public static String folderName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String base = fileName;
        int longest = 0;
        for (ArchiveKind kind : values()) {
            for (String extension : kind.extensions) {
                if (lower.endsWith(extension) && extension.length() > longest) {
                    longest = extension.length();
                    base = fileName.substring(0, fileName.length() - extension.length());
                }
            }
        }
        base = base.strip();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base) || base.contains("/")) {
            return "extracted";
        }
        return base;
    }
}
