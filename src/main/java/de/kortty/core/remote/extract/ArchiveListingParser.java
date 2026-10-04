package de.kortty.core.remote.extract;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the member listings the archive tools print. Every parser is strict: output it cannot read
 * unambiguously throws {@link IllegalArgumentException}, and the caller refuses the archive.
 */
final class ArchiveListingParser {

    /** A parsed listing. */
    record Listing(List<ArchiveMember> members, boolean encrypted) {
    }

    /** What {@code unzip -Z} (zipinfo's medium format) says about the whole archive. */
    record ZipInfo(int declaredCount, boolean encrypted) {
    }

    private static final Pattern ZIPINFO_FOOTER = Pattern.compile("^(\\d+) files?, .*");
    private static final Pattern GNU_TAR_LINE = Pattern.compile("^(\\S{10})\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+ (.*)$");

    private ArchiveListingParser() {
    }

    /** {@code unzip -Z1}: one member name per line. */
    static List<String> lines(String output) {
        List<String> names = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            names.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        while (!names.isEmpty() && names.get(names.size() - 1).isEmpty()) {
            names.remove(names.size() - 1);
        }
        return names;
    }

    /**
     * {@code unzip -Z}: the member count from the footer and whether any member is encrypted (the
     * first letter of the fifth column is upper case for an encrypted member).
     */
    static ZipInfo zipInfo(String output) {
        int count = -1;
        boolean encrypted = false;
        for (String line : lines(output)) {
            Matcher footer = ZIPINFO_FOOTER.matcher(line);
            if (footer.matches()) {
                count = Integer.parseInt(footer.group(1));
                continue;
            }
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 9 && fields[4].length() == 2 && "tTbB".indexOf(fields[4].charAt(0)) >= 0
                    && Character.isUpperCase(fields[4].charAt(0))) {
                encrypted = true;
            }
        }
        if (count < 0) {
            throw new IllegalArgumentException("zipinfo printed no member count");
        }
        return new ZipInfo(count, encrypted);
    }

    /** The ZIP members, checked against the member count so a name with a line break cannot hide. */
    static Listing zip(String namesOutput, String infoOutput) {
        List<String> names = lines(namesOutput);
        ZipInfo info = zipInfo(infoOutput);
        if (names.size() != info.declaredCount()) {
            throw new IllegalArgumentException("zip listing and member count disagree");
        }
        return new Listing(names.stream().map(ArchiveMember::of).toList(), info.encrypted());
    }

    /**
     * GNU {@code tar --quoting-style=escape -tv}: mode, owner/group, size, date, time, then the
     * escaped name, followed by {@code " -> target"} for a symlink or {@code " link to target"} for a
     * hardlink. With the escape quoting style a name never contains a raw line break, and a link
     * line whose separator occurs more than once is refused as ambiguous.
     */
    static Listing gnuTar(String output) {
        List<ArchiveMember> members = new ArrayList<>();
        for (String line : lines(output)) {
            Matcher matcher = GNU_TAR_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("unreadable tar listing line");
            }
            char kind = matcher.group(1).charAt(0);
            String rest = matcher.group(2);
            members.add(switch (kind) {
                case '-' -> new ArchiveMember(unescape(rest), ArchiveMember.Type.FILE, null);
                case 'd' -> new ArchiveMember(unescape(rest), ArchiveMember.Type.DIRECTORY, null);
                case 'l' -> link(rest, " -> ", ArchiveMember.Type.SYMLINK);
                case 'h' -> link(rest, " link to ", ArchiveMember.Type.HARDLINK);
                default -> new ArchiveMember(unescape(rest), ArchiveMember.Type.SPECIAL, null);
            });
        }
        return new Listing(members, false);
    }

    private static ArchiveMember link(String rest, String separator, ArchiveMember.Type type) {
        int first = rest.indexOf(separator);
        if (first < 0 || first != rest.lastIndexOf(separator)) {
            throw new IllegalArgumentException("ambiguous link line in tar listing");
        }
        return new ArchiveMember(unescape(rest.substring(0, first)), type,
            unescape(rest.substring(first + separator.length())));
    }

    /**
     * Other tars (bsdtar, BusyBox): names from {@code tar -t}, entry types from the first column of
     * {@code tar -tv}. Their link targets are not read: a symlink is left to the check after
     * extraction, and a hardlink is refused because its target is unknown. BusyBox shows a hardlink
     * as a regular file followed by {@code " -> target"}, so such a line counts as a hardlink too
     * (a file whose own name contains {@code " -> "} is refused with it, on the safe side).
     */
    static Listing plainTar(String namesOutput, String verboseOutput) {
        List<String> names = lines(namesOutput);
        List<String> verbose = lines(verboseOutput);
        if (names.size() != verbose.size()) {
            throw new IllegalArgumentException("tar listings disagree");
        }
        List<ArchiveMember> members = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String line = verbose.get(i);
            char kind = line.isEmpty() ? '?' : line.charAt(0);
            ArchiveMember.Type type = switch (kind) {
                // BusyBox lists a hardlink as a plain file with " -> target"
                case '-' -> line.contains(" -> ") ? ArchiveMember.Type.HARDLINK : ArchiveMember.Type.FILE;
                case 'd' -> ArchiveMember.Type.DIRECTORY;
                case 'l' -> ArchiveMember.Type.SYMLINK;
                case 'h' -> ArchiveMember.Type.HARDLINK;
                default -> ArchiveMember.Type.SPECIAL;
            };
            members.add(new ArchiveMember(names.get(i), type, null));
        }
        return new Listing(members, false);
    }

    /**
     * {@code 7z l -slt -ba}: blocks of {@code Key = Value} lines separated by blank lines. A line
     * without {@code " = "} (for example the rest of a name with a line break) makes the listing
     * unreadable.
     */
    static Listing sevenZip(String output) {
        List<ArchiveMember> members = new ArrayList<>();
        boolean encrypted = false;
        String path = null;
        boolean folder = false;
        String attributes = "";
        String symlink = null;
        String hardlink = null;
        List<String> all = new ArrayList<>(lines(output));
        all.add("");
        for (String line : all) {
            if (line.isEmpty() || line.chars().allMatch(c -> c == '-')) {
                if (path != null) {
                    members.add(sevenZipMember(path, folder, attributes, symlink, hardlink));
                }
                path = null;
                folder = false;
                attributes = "";
                symlink = null;
                hardlink = null;
                continue;
            }
            int separator = line.indexOf(" = ");
            String key;
            String value;
            if (separator > 0) {
                key = line.substring(0, separator);
                value = line.substring(separator + 3);
            } else if (line.endsWith(" =")) {
                key = line.substring(0, line.length() - 2);
                value = "";
            } else {
                throw new IllegalArgumentException("unreadable 7z listing line");
            }
            switch (key) {
                case "Path" -> path = value;
                case "Folder" -> folder = "+".equals(value);
                case "Attributes" -> attributes = value;
                case "Encrypted" -> encrypted |= "+".equals(value);
                case "Symbolic Link" -> symlink = value.isEmpty() ? null : value;
                case "Hard Link" -> hardlink = value.isEmpty() ? null : value;
                default -> {
                    // other properties do not matter here
                }
            }
        }
        return new Listing(members, encrypted);
    }

    private static ArchiveMember sevenZipMember(String path, boolean folder, String attributes, String symlink,
                                                String hardlink) {
        if (hardlink != null) {
            return new ArchiveMember(path, ArchiveMember.Type.HARDLINK, hardlink);
        }
        // "A -lrwxrwxrwx": the Unix mode follows the Windows attribute letters
        boolean unixLink = attributes.matches(".*(^|\\s|-)l[rwxsStT-]{9}.*");
        if (symlink != null || unixLink) {
            return new ArchiveMember(path, ArchiveMember.Type.SYMLINK, symlink);
        }
        return new ArchiveMember(path, folder ? ArchiveMember.Type.DIRECTORY : ArchiveMember.Type.FILE, null);
    }

    /** Undoes GNU tar's escape quoting style: C escapes and three-digit octal bytes. */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            if (b != '\\') {
                out.write(b);
                continue;
            }
            if (i + 1 >= bytes.length) {
                throw new IllegalArgumentException("dangling escape in tar listing");
            }
            byte next = bytes[++i];
            switch (next) {
                case '\\' -> out.write('\\');
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case 'a' -> out.write(7);
                case 'b' -> out.write('\b');
                case 'f' -> out.write('\f');
                case 'v' -> out.write(11);
                case '?' -> out.write(127);
                case '"', '\'' -> out.write(next);
                default -> {
                    if (next >= '0' && next <= '7' && i + 2 < bytes.length
                            && isOctal(bytes[i + 1]) && isOctal(bytes[i + 2])) {
                        out.write(((next - '0') << 6) | ((bytes[i + 1] - '0') << 3) | (bytes[i + 2] - '0'));
                        i += 2;
                    } else {
                        throw new IllegalArgumentException("unknown escape in tar listing");
                    }
                }
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isOctal(byte b) {
        return b >= '0' && b <= '7';
    }
}
