package de.kortty.core.remote;

import java.util.List;
import java.util.Objects;

/**
 * Builds the shell command that packs remote files into an archive on the server.
 *
 * <p>There is deliberately no password parameter. Info-ZIP's {@code zip -P} and 7-Zip's
 * {@code -p<password>} put the password on the command line, where every other user of the server
 * can read it with {@code ps}; {@code zip -e} and a bare {@code 7z -p} read it from a terminal only
 * ({@code zip} refuses with "stderr is not a tty" on an exec channel), and korTTY never gives these
 * channels a pty. So password-protected remote archives are not offered; see
 * {@link #supportsPassword(Format)}.
 */
public final class RemoteArchiveCommands {

    /** The remote archive formats the SFTP manager can create. */
    public enum Format {
        ZIP,
        TAR_BZ2,
        SEVEN_ZIP
    }

    private RemoteArchiveCommands() {
    }

    /**
     * Whether a password can be applied to this format without it showing up on the command line
     * or needing a terminal. No supported tool can, so this is false for every format.
     */
    public static boolean supportsPassword(Format format) {
        Objects.requireNonNull(format, "format");
        return false;
    }

    /**
     * The archive command.
     *
     * @param compression 0 (store) to 9 (best); values outside are clamped
     * @param excludePatterns patterns passed to the tool's exclude option, may be null
     */
    public static String build(Format format, List<String> files, String archivePath, int compression,
                               List<String> excludePatterns) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(files, "files");
        if (archivePath == null || archivePath.isBlank()) {
            throw new IllegalArgumentException("An archive path is required.");
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("At least one file is required.");
        }
        int level = Math.max(0, Math.min(9, compression));
        List<String> exclude = excludePatterns != null ? excludePatterns : List.of();
        StringBuilder cmd = new StringBuilder();
        switch (format) {
            case ZIP -> {
                cmd.append("zip -r -").append(level).append(' ').append(pathArgument(archivePath));
                for (String pattern : exclude) {
                    cmd.append(" -x ").append(RemoteShell.quote(pattern));
                }
                appendFiles(cmd, files);
            }
            case TAR_BZ2 -> {
                // -j = bzip2; the level travels in the BZIP2 environment variable
                cmd.append("BZIP2=-").append(level).append(" tar -cjf ").append(pathArgument(archivePath));
                for (String pattern : exclude) {
                    cmd.append(' ').append(RemoteShell.quote("--exclude=" + pattern));
                }
                appendFiles(cmd, files);
            }
            case SEVEN_ZIP -> {
                // p7zip installs 7za on some systems
                cmd.append("\"$(command -v 7z || command -v 7za)\" a -mx=").append(level);
                for (String pattern : exclude) {
                    cmd.append(' ').append(RemoteShell.quote("-x!" + pattern));
                }
                cmd.append(' ').append(pathArgument(archivePath));
                appendFiles(cmd, files);
            }
        }
        return cmd.toString();
    }

    private static void appendFiles(StringBuilder cmd, List<String> files) {
        for (String file : files) {
            cmd.append(' ').append(pathArgument(file));
        }
    }

    /** Quotes a path; a relative one starting with '-' gets a "./" so the tool cannot read it as an option. */
    public static String pathArgument(String value) {
        return RemoteShell.quote(value.startsWith("-") ? "./" + value : value);
    }
}
