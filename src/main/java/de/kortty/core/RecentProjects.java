package de.kortty.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The projects <i>File → Open Recent</i> lists. korTTY remembers the project files you opened or
 * saved last ({@link #remember}, kept in {@code GlobalSettings.recentProjectPaths}, newest first)
 * and adds the projects in its own project folder ({@code ~/.kortty/projects}), so projects saved
 * there before this list existed show up too. A file that no longer exists is left out.
 *
 * <p>{@link #list} reads the file system, so call it off the FX thread: a remembered file can sit on
 * a network share that takes long to answer.
 */
public final class RecentProjects {

    /** How many projects the list remembers and the menu shows. */
    public static final int MAX_ENTRIES = 10;

    private static final String EXTENSION = ".kortty";
    private static final String SEPARATOR = " — ";
    private static final int MAX_FOLDER_LABEL_LENGTH = 60;

    private RecentProjects() {
    }

    /**
     * The remembered paths after {@code project} was opened or saved: it moves to the front as an
     * absolute path, an earlier entry for the same file goes, and at most {@link #MAX_ENTRIES} stay.
     * Blank entries are dropped. Returns a new list; {@code recent} is not changed.
     */
    public static List<String> remember(List<String> recent, Path project) {
        Set<String> paths = new LinkedHashSet<>();
        if (project != null) {
            paths.add(key(project));
        }
        if (recent != null) {
            for (String entry : recent) {
                Path path = parse(entry);
                if (path != null) {
                    paths.add(key(path));
                }
            }
        }
        return paths.stream().limit(MAX_ENTRIES).toList();
    }

    /**
     * The projects to list, at most {@code max}: first the remembered ones that are still regular
     * files, in their order, then the projects of the project folder that are not among them, the
     * one changed last first. A folder project changed before {@code clearedAt} (epoch milliseconds,
     * when <i>Clear List</i> was chosen; {@code 0} for never) is left out, as Clear List emptied the
     * menu then.
     *
     * @param recent         the remembered paths, newest first
     * @param folderProjects the {@code .kortty} files of the project folder, in any order
     */
    public static List<Path> list(List<String> recent, Collection<Path> folderProjects, long clearedAt, int max) {
        if (max <= 0) {
            return List.of();
        }
        Set<Path> listed = new LinkedHashSet<>();
        if (recent != null) {
            for (String entry : recent) {
                Path path = parse(entry);
                if (path != null && Files.isRegularFile(path)) {
                    listed.add(path);
                }
            }
        }
        if (folderProjects != null && listed.size() < max) {
            Map<Path, Long> changed = new HashMap<>();
            for (Path candidate : folderProjects) {
                if (candidate == null) {
                    continue;
                }
                Path path = normalize(candidate);
                Long modified = lastModified(path);
                if (modified != null && modified > clearedAt && !listed.contains(path)) {
                    changed.put(path, modified);
                }
            }
            changed.entrySet().stream()
                    .sorted(Map.Entry.<Path, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> listed.add(entry.getKey()));
        }
        return listed.stream().limit(max).toList();
    }

    /**
     * The menu label of {@code project}: its file name without {@code .kortty} and the folder it is
     * in, with the home folder shown as {@code ~} and a long folder shortened at the front, so two
     * projects of the same name in different folders can be told apart.
     */
    public static String label(Path project, Path home) {
        Path fileName = project.getFileName();
        String name = fileName != null ? fileName.toString() : project.toString();
        if (name.length() > EXTENSION.length()
                && name.regionMatches(true, name.length() - EXTENSION.length(), EXTENSION, 0, EXTENSION.length())) {
            name = name.substring(0, name.length() - EXTENSION.length());
        }
        Path parent = project.getParent();
        if (parent == null) {
            return name;
        }
        return name + SEPARATOR + folderLabel(parent, home);
    }

    private static String folderLabel(Path folder, Path home) {
        String label;
        if (home != null && folder.equals(home)) {
            label = "~";
        } else if (home != null && home.getNameCount() > 0 && folder.startsWith(home)) {
            label = "~" + folder.getFileSystem().getSeparator() + home.relativize(folder);
        } else {
            label = folder.toString();
        }
        if (label.length() > MAX_FOLDER_LABEL_LENGTH) {
            label = "…" + label.substring(label.length() - (MAX_FOLDER_LABEL_LENGTH - 1));
        }
        return label;
    }

    private static String key(Path path) {
        return normalize(path).toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /** The remembered entry as an absolute path, or {@code null} for a blank or malformed entry. */
    private static Path parse(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        try {
            return normalize(Path.of(entry.strip()));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The time {@code path} was changed last, or {@code null} when it is no regular file or cannot be read. */
    private static Long lastModified(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
