package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free names for "keep both": {@code report.txt} becomes {@code report (1).txt}, then
 * {@code report (2).txt}. The number goes before the extension, a double extension such as
 * {@code .tar.gz} stays together, a dotfile such as {@code .bashrc} gets the number at the end, and
 * a name that already ends in {@code (n)} counts on from n.
 */
public final class UniqueNames {

    /** How many numbered candidates are tried before giving up. */
    public static final int MAX_TRIES = 10_000;
    /** Longer "extensions" (or ones with spaces) are treated as part of the name. */
    private static final int MAX_EXTENSION_LENGTH = 10;
    private static final Pattern NUMBERED = Pattern.compile("^(.*\\S) \\((\\d{1,5})\\)$");

    private UniqueNames() {
    }

    /**
     * The first numbered variant of {@code name} for which {@code taken} is false.
     *
     * @param taken whether a candidate already exists; make it case-insensitive where the target
     *              file store is (see {@link #inLocalFolder(Path)} and {@link #among})
     * @throws IOException when {@link #MAX_TRIES} candidates are all taken
     */
    public static String next(String name, Predicate<String> taken) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
        int extensionStart = extensionStart(name);
        String base = extensionStart < 0 ? name : name.substring(0, extensionStart);
        String extension = extensionStart < 0 ? "" : name.substring(extensionStart);
        int first = 1;
        Matcher numbered = NUMBERED.matcher(base);
        if (numbered.matches()) {
            base = numbered.group(1);
            first = Integer.parseInt(numbered.group(2)) + 1;
        }
        for (int i = 0; i < MAX_TRIES; i++) {
            String candidate = base + " (" + (first + i) + ")" + extension;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IOException(I18n.get("sftp.error.noUniqueName", name));
    }

    /**
     * A predicate over the names in a remote listing. With {@code ignoreCase} two names differing
     * only in case count as the same.
     */
    public static Predicate<String> among(Collection<String> existing, boolean ignoreCase) {
        if (!ignoreCase) {
            Set<String> exact = Set.copyOf(existing);
            return exact::contains;
        }
        Set<String> folded = new TreeSet<>();
        existing.forEach(name -> folded.add(name.toLowerCase(Locale.ROOT)));
        return candidate -> folded.contains(candidate.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a name is taken in a local folder, without following symbolic links. A dangling link
     * counts as taken, and on a case-insensitive file store so does a name that differs only in
     * case. A part file korTTY would write for the candidate also counts.
     */
    public static Predicate<String> inLocalFolder(Path folder) {
        boolean ignoreCase = LocalNames.isCaseInsensitive(folder);
        return candidate -> {
            try {
                for (String name : new String[]{candidate, PartFiles.partName(candidate)}) {
                    if (Files.exists(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                        return true;
                    }
                    if (ignoreCase && LocalNames.caseCollision(folder, name).isPresent()) {
                        return true;
                    }
                }
                return false;
            } catch (IOException | RuntimeException e) {
                return true; // unknown: never hand out a name that might be in use
            }
        };
    }

    /**
     * Where the extension starts, or -1. Leading dots belong to the name ({@code .bashrc}); a
     * {@code .tar.*} pair is one extension.
     */
    static int extensionStart(String name) {
        int leading = 0;
        while (leading < name.length() && name.charAt(leading) == '.') {
            leading++;
        }
        int last = name.lastIndexOf('.');
        if (last <= leading || last == name.length() - 1 || !plausibleExtension(name.substring(last + 1))) {
            return -1;
        }
        int previous = name.lastIndexOf('.', last - 1);
        if (previous > leading && name.substring(previous + 1, last).equalsIgnoreCase("tar")) {
            return previous;
        }
        return last;
    }

    private static boolean plausibleExtension(String extension) {
        if (extension.length() > MAX_EXTENSION_LENGTH) {
            return false;
        }
        for (int i = 0; i < extension.length(); i++) {
            if (Character.isWhitespace(extension.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
