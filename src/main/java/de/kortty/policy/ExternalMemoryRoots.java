package de.kortty.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The folders external memory files may come from ({@code [rule.ai-memory] external-file-roots}). A file
 * is allowed when it lies in or below a folder of every list, the same most-restrictive combination
 * {@link WebhookHostAllowlist} uses for hosts. An empty list allows every folder.
 *
 * <p>Entries are absolute or {@code ~/}-relative as the policy file writes them. Folders and files are compared by
 * their real paths: links in the existing part of an entry or of a file are resolved first, so an entry given through
 * a link (on macOS, {@code /tmp} is one) still matches the real path that a file is checked by.</p>
 *
 * @param lists the allowlists that apply, each already expanded and normalized; an empty inner list allows any folder
 */
public record ExternalMemoryRoots(List<List<Path>> lists) {

    /** Nothing set: every folder is allowed. */
    public static final ExternalMemoryRoots NONE = new ExternalMemoryRoots(List.of());

    public ExternalMemoryRoots {
        List<List<Path>> copy = new ArrayList<>();
        if (lists != null) {
            for (List<Path> list : lists) {
                copy.add(list == null ? List.of() : List.copyOf(list));
            }
        }
        lists = List.copyOf(copy);
    }

    /** One rule's list, from the entries of the policy file. */
    public static ExternalMemoryRoots of(List<String> entries) {
        List<Path> roots = new ArrayList<>();
        for (String entry : entries == null ? List.<String>of() : entries) {
            roots.add(normalize(entry));
        }
        return new ExternalMemoryRoots(List.of(roots));
    }

    /** Both lists apply (same-tier conflict): a file must pass each. */
    public ExternalMemoryRoots and(ExternalMemoryRoots other) {
        List<List<Path>> combined = new ArrayList<>(lists);
        combined.addAll(other.lists);
        return new ExternalMemoryRoots(combined);
    }

    /** True when at least one non-empty list limits the folders. */
    public boolean restricts() {
        return lists.stream().anyMatch(list -> !list.isEmpty());
    }

    /** Whether {@code file} may be used as an external memory file. */
    public boolean allows(Path file) {
        if (!restricts()) {
            return true;
        }
        Path normalized = canonical(file);
        for (List<Path> list : lists) {
            if (!list.isEmpty() && list.stream().noneMatch(normalized::startsWith)) {
                return false;
            }
        }
        return true;
    }

    /** Expands a leading {@code ~} to the user's home and normalizes the path, resolving its existing part. */
    static Path normalize(String entry) {
        String expanded = entry;
        if ("~".equals(entry) || entry.startsWith("~/")) {
            expanded = System.getProperty("user.home") + entry.substring(1);
        }
        return canonical(Path.of(expanded));
    }

    /**
     * The absolute, normalized path with every link in its existing part resolved. A part that does not exist yet stays
     * as written, so a folder created later is still compared by its path.
     */
    static Path canonical(Path path) {
        Path existing = path.toAbsolutePath().normalize();
        Path missing = null;
        while (!Files.exists(existing) && existing.getParent() != null) {
            Path name = existing.getFileName();
            missing = missing == null ? name : name.resolve(missing);
            existing = existing.getParent();
        }
        try {
            Path real = existing.toRealPath();
            return missing == null ? real : real.resolve(missing).normalize();
        } catch (IOException | RuntimeException unresolved) {
            return path.toAbsolutePath().normalize();
        }
    }
}
