package de.kortty.core.remote.extract;

import de.kortty.core.remote.RemoteShell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The shell commands of a remote extraction. Every path is absolute and goes through
 * {@link RemoteShell#quote}; every tool runs with {@code LC_ALL=C} (stable listings) and stdin from
 * {@code /dev/null} (a tool that would ask a question fails instead of waiting).
 */
public final class ExtractCommandBuilder {

    /** The prefix of the private staging folder next to the archive. */
    public static final String STAGING_PREFIX = ".kortty-extract.";
    /** Exit status of {@link #finish} when every candidate name is taken. */
    public static final int NO_FREE_NAME = 73;

    private static final String SEVEN_ZIP = "\"$(command -v 7z || command -v 7za)\"";

    private ExtractCommandBuilder() {
    }

    /** Succeeds (exit 0) when the format's tool is installed. */
    public static String toolProbe(ArchiveKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (kind == ArchiveKind.SEVEN_ZIP) {
            return "command -v 7z >/dev/null 2>&1 || command -v 7za >/dev/null 2>&1";
        }
        return "command -v " + kind.tool() + " >/dev/null 2>&1";
    }

    /** Prints tar's version line; GNU tar says "GNU tar". */
    public static String gnuTarProbe() {
        return "LC_ALL=C tar --version 2>/dev/null | head -n 1";
    }

    /**
     * The listing commands, run one after the other: ZIP prints names ({@code -Z1}) and then the
     * medium listing with the count and the encryption flags; GNU tar one escaped verbose listing;
     * other tars names and then a verbose listing; 7z one technical listing.
     */
    public static List<String> listCommands(ArchiveKind kind, String archive, boolean gnuTar) {
        Objects.requireNonNull(kind, "kind");
        String quoted = absolute(archive);
        List<String> commands = new ArrayList<>();
        switch (kind) {
            case ZIP -> {
                commands.add("LC_ALL=C unzip -Z1 " + quoted + " </dev/null");
                commands.add("LC_ALL=C unzip -Z " + quoted + " </dev/null");
            }
            case SEVEN_ZIP -> commands.add("LC_ALL=C " + SEVEN_ZIP + " l -slt -ba " + quoted + " </dev/null");
            default -> {
                if (gnuTar) {
                    commands.add("LC_ALL=C tar --quoting-style=escape -tv" + compression(kind) + " -f " + quoted
                        + " </dev/null");
                } else {
                    commands.add("LC_ALL=C tar -t" + compression(kind) + " -f " + quoted + " </dev/null");
                    commands.add("LC_ALL=C tar -tv" + compression(kind) + " -f " + quoted + " </dev/null");
                }
            }
        }
        return commands;
    }

    /** Creates the private (mode 0700) staging folder in {@code parent} and prints its path. */
    public static String makeStaging(String parent) {
        return "mktemp -d " + RemoteShell.quote(stagingTemplate(parent));
    }

    static String stagingTemplate(String parent) {
        String folder = absoluteFolder(parent);
        return folder + (folder.endsWith("/") ? "" : "/") + STAGING_PREFIX + "XXXXXX";
    }

    /**
     * Whether {@code staging} is what {@link #makeStaging} can have printed for {@code parent}: a
     * direct child named {@code .kortty-extract.} plus random characters. Anything else is never
     * used, so cleanup can never remove a folder korTTY did not create.
     */
    public static boolean isStagingPath(String parent, String staging) {
        if (staging == null) {
            return false;
        }
        String template = stagingTemplate(parent);
        String prefix = template.substring(0, template.length() - "XXXXXX".length());
        if (!staging.startsWith(prefix) || staging.length() <= prefix.length()) {
            return false;
        }
        String suffix = staging.substring(prefix.length());
        return suffix.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || c == '_' || c == '-' || c == '.');
    }

    /** Unpacks the archive into the staging folder; never restores owners, never allows "..". */
    public static String extract(ArchiveKind kind, String archive, String staging) {
        Objects.requireNonNull(kind, "kind");
        String quotedArchive = absolute(archive);
        String quotedStaging = absolute(staging);
        return switch (kind) {
            // -n: never overwrite (duplicate members); no -: so "../" stays refused by unzip too
            case ZIP -> "LC_ALL=C unzip -qq -n " + quotedArchive + " -d " + quotedStaging + " </dev/null";
            // -aos: skip duplicates; -o takes the folder without a space
            case SEVEN_ZIP -> "LC_ALL=C " + SEVEN_ZIP + " x -y -bd -aos " + RemoteShell.quote("-o" + staging) + " "
                + quotedArchive + " </dev/null";
            default -> "cd " + quotedStaging + " && LC_ALL=C tar -x --no-same-owner" + compression(kind) + " -f "
                + quotedArchive + " </dev/null";
        };
    }

    /**
     * Prints every symlink below the staging folder and its target, NUL-separated pairs, without
     * following any link ({@code -P}).
     */
    public static String linkScan(String staging) {
        String script = "for f in \"$@\"; do printf '%s\\0%s\\0' \"$f\" \"$(readlink -- \"$f\")\"; done";
        return "find -P " + absolute(staging) + " -type l -exec sh -c " + RemoteShell.quote(script) + " sh {} +";
    }

    /**
     * Gives the staging folder the permissions of a normal new folder (from the umask) and renames
     * it to the first candidate that does not exist yet, printing that path. Exits with
     * {@link #NO_FREE_NAME} when all candidates are taken.
     */
    public static String finish(String staging, List<String> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("At least one target name is required.");
        }
        String quotedStaging = absolute(staging);
        StringBuilder list = new StringBuilder();
        for (String candidate : candidates) {
            list.append(' ').append(absolute(candidate));
        }
        return "chmod \"$(umask -S)\" " + quotedStaging + " && for t in" + list
            + "; do if [ ! -e \"$t\" ] && [ ! -L \"$t\" ]; then mv -- " + quotedStaging
            + " \"$t\" && printf '%s' \"$t\"; exit; fi; done; exit " + NO_FREE_NAME;
    }

    /** Removes the staging folder, first making its subfolders writable so nothing is left. */
    public static String cleanup(String staging) {
        String quoted = absolute(staging);
        return "chmod -R u+rwx " + quoted + " 2>/dev/null; rm -rf -- " + quoted;
    }

    private static String compression(ArchiveKind kind) {
        String flag = kind.tarCompressionFlag();
        return flag.isEmpty() ? "" : " " + flag;
    }

    private static String absolute(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Remote paths must be absolute.");
        }
        return RemoteShell.quote(path);
    }

    private static String absoluteFolder(String path) {
        absolute(path);
        return path;
    }
}
