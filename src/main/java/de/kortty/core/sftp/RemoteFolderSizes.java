package de.kortty.core.sftp;

import de.kortty.core.remote.RemoteShell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How much disk space server folders take, measured with {@code du -sk}: the SFTP listing gives a
 * folder no size, and counting through SFTP would list every file over the connection. {@code -s}
 * and {@code -k} are POSIX, so GNU, BSD, macOS and BusyBox du all understand the command. The
 * figure is the space the files occupy on disk, in KiB blocks, not the sum of their lengths.
 */
public final class RemoteFolderSizes {

    private RemoteFolderSizes() {
    }

    /** {@code du -sk -- 'a' 'b'}: one total per folder; every path is quoted for the shell. */
    public static String command(List<String> folders) {
        if (folders.isEmpty()) {
            throw new IllegalArgumentException("No folders to measure.");
        }
        List<String> arguments = new ArrayList<>(List.of("du", "-sk", "--"));
        arguments.addAll(folders);
        return RemoteShell.join(arguments);
    }

    /**
     * The sizes in bytes from du's output ({@code <KiB><TAB><path>} per line), only for the
     * {@code requested} paths: du may print a path with a trailing slash removed or added, and
     * a line for anything else (an error that slipped into stdout) is ignored. A folder du could
     * read only in part still gets the total of what it could read.
     */
    public static Map<String, Long> parse(String output, Collection<String> requested) {
        Set<String> wanted = new HashSet<>(requested);
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String line : (output == null ? "" : output).split("\\R")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            long kib;
            try {
                kib = Long.parseLong(line.substring(0, tab).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String path = line.substring(tab + 1);
            String match = wanted.contains(path) ? path
                : wanted.contains(path + "/") ? path + "/"
                : path.endsWith("/") && wanted.contains(path.substring(0, path.length() - 1))
                    ? path.substring(0, path.length() - 1)
                    : null;
            if (match != null && kib >= 0) {
                sizes.put(match, kib * 1024L);
            }
        }
        return sizes;
    }
}
