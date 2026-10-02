package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetFolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The files and directories a set of snippets becomes on disk: each snippet's file lives in the
 * directory its folder chain spells out, relative to a base folder. Shared by the folder export,
 * the copy to a terminal's working directory and the project analysis, so all three see the same
 * paths. Paths always use {@code /}.
 */
public final class SnippetFolderLayout {

    /** One snippet file: its relative path, the snippet and the mode it is written with. */
    public record Entry(String relativePath, Snippet snippet, boolean executable) {
        public int mode() {
            return executable ? SnippetExecutableSupport.EXECUTABLE_MODE : SnippetExecutableSupport.REGULAR_MODE;
        }

        public String fileName() {
            int slash = relativePath.lastIndexOf('/');
            return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        }

        public String directory() {
            int slash = relativePath.lastIndexOf('/');
            return slash >= 0 ? relativePath.substring(0, slash) : "";
        }
    }

    private final List<String> directories;
    private final List<Entry> entries;

    private SnippetFolderLayout(List<String> directories, List<Entry> entries) {
        this.directories = List.copyOf(directories);
        this.entries = List.copyOf(entries);
    }

    /** Every directory to create, parents before children (includes empty folders of a folder export). */
    public List<String> directories() {
        return directories;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty() && directories.isEmpty();
    }

    /**
     * The whole folder {@code folderId} with everything below it. With {@code includeFolderItself}
     * the paths start with the folder's own name ({@code tools/lib/x.sh}), as when the folder is
     * copied into a directory; otherwise they are relative to it ({@code lib/x.sh}).
     * {@code folderId == null} lays out the whole library.
     */
    public static SnippetFolderLayout ofFolder(SnippetManager manager, String folderId, boolean includeFolderItself) {
        Objects.requireNonNull(manager, "manager");
        List<Snippet> scope = manager.snippetsInFolder(folderId, true).stream()
            .filter(snippet -> snippet != null && !snippet.isPolicyManaged())
            .toList();
        String base = includeFolderItself && folderId != null
            ? manager.findFolder(folderId).map(SnippetFolder::getParentId).orElse(null)
            : folderId;
        Set<String> folderIds = folderId == null
            ? manager.getAllFolders().stream().map(SnippetFolder::getId).collect(java.util.stream.Collectors.toSet())
            : manager.descendantFolderIds(folderId);
        return build(manager, scope, base, folderIds);
    }

    /**
     * The given snippets, each in the directory of its folder relative to {@code baseFolderId}
     * ({@code null} = the library root). A snippet outside the base folder lands at the top level.
     */
    public static SnippetFolderLayout ofSnippets(SnippetManager manager, Collection<Snippet> snippets,
                                                 String baseFolderId) {
        Objects.requireNonNull(manager, "manager");
        List<Snippet> scope = snippets == null ? List.of()
            : snippets.stream().filter(Objects::nonNull).toList();
        return build(manager, scope, baseFolderId, Set.of());
    }

    /** The given snippets side by side without any directories (a flat export). */
    public static SnippetFolderLayout flat(Collection<Snippet> snippets) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        if (snippets != null) {
            for (Snippet snippet : snippets) {
                if (snippet == null) {
                    continue;
                }
                String path = uniquePath("", SnippetExecutableSupport.fileNameOf(snippet), used);
                entries.add(new Entry(path, snippet, SnippetExecutableSupport.isExecutable(snippet)));
            }
        }
        return new SnippetFolderLayout(List.of(), entries);
    }

    private static SnippetFolderLayout build(SnippetManager manager, List<Snippet> scope, String baseFolderId,
                                             Set<String> extraFolderIds) {
        Map<String, String> pathCache = new HashMap<>();
        Set<String> directories = new LinkedHashSet<>();
        for (String folderId : extraFolderIds) {
            addDirectoryChain(directories, relativeFolderPath(manager, folderId, baseFolderId, pathCache));
        }
        List<Snippet> ordered = new ArrayList<>(scope);
        ordered.sort(Comparator
            .comparing((Snippet snippet) -> relativeFolderPath(manager, snippet.getFolderId(), baseFolderId, pathCache),
                String.CASE_INSENSITIVE_ORDER)
            .thenComparing(snippet -> snippet.getName() != null ? snippet.getName() : "", String.CASE_INSENSITIVE_ORDER));
        Map<String, Integer> used = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        for (Snippet snippet : ordered) {
            String directory = relativeFolderPath(manager, snippet.getFolderId(), baseFolderId, pathCache);
            addDirectoryChain(directories, directory);
            String path = uniquePath(directory, SnippetExecutableSupport.fileNameOf(snippet), used);
            entries.add(new Entry(path, snippet, SnippetExecutableSupport.isExecutable(snippet)));
        }
        List<String> sortedDirectories = new ArrayList<>(directories);
        sortedDirectories.sort(Comparator.comparingInt((String dir) -> dir.split("/").length)
            .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return new SnippetFolderLayout(sortedDirectories, entries);
    }

    /** The folder's directory path below {@code baseFolderId}; {@code ""} when outside or at the base. */
    private static String relativeFolderPath(SnippetManager manager, String folderId, String baseFolderId,
                                             Map<String, String> cache) {
        if (folderId == null) {
            return "";
        }
        return cache.computeIfAbsent(folderId, id -> {
            List<SnippetFolder> chain = manager.folderChain(id);
            int start = 0;
            if (baseFolderId != null) {
                start = -1;
                for (int i = 0; i < chain.size(); i++) {
                    if (baseFolderId.equals(chain.get(i).getId())) {
                        start = i + 1;
                        break;
                    }
                }
                if (start < 0) {
                    return "";
                }
            }
            List<String> segments = new ArrayList<>();
            for (int i = start; i < chain.size(); i++) {
                String segment = SnippetManager.sanitizeFolderName(chain.get(i).getName());
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
            }
            return String.join("/", segments);
        });
    }

    private static void addDirectoryChain(Set<String> directories, String directory) {
        if (directory == null || directory.isEmpty()) {
            return;
        }
        String[] segments = directory.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(segment);
            directories.add(prefix.toString());
        }
    }

    private static String uniquePath(String directory, String fileName, Map<String, Integer> used) {
        String path = directory.isEmpty() ? fileName : directory + "/" + fileName;
        String key = path.toLowerCase(Locale.ROOT);
        int count = used.merge(key, 1, Integer::sum);
        if (count == 1) {
            return path;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        while (true) {
            String candidateName = stem + "-" + count + extension;
            String candidate = directory.isEmpty() ? candidateName : directory + "/" + candidateName;
            if (used.putIfAbsent(candidate.toLowerCase(Locale.ROOT), 1) == null) {
                return candidate;
            }
            count++;
        }
    }
}
