package de.kortty.core.remote.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Refuses archive members that could write outside the folder they are unpacked into ("zip slip"),
 * before anything is extracted.
 *
 * <p>Both {@code /} and {@code \} count as separators here, so {@code ..\..\x} is caught as well as
 * {@code ../../x}. A name is refused when it is absolute, starts with a drive letter, contains a
 * {@code ..} segment that climbs above the archive root, or contains NUL or another control
 * character (a newline would make the listing ambiguous). A symlink is refused when its target is
 * absolute or climbs out of the archive root from the link's own folder; a hardlink when its target
 * is not itself a safe member path. Special entries (devices, FIFOs) are refused outright.
 */
public final class ArchiveMemberValidator {

    /** Why a member was refused. */
    public enum Reason {
        EMPTY_NAME,
        CONTROL_CHARACTER,
        ABSOLUTE_PATH,
        DRIVE_LETTER,
        PARENT_TRAVERSAL,
        LINK_ESCAPE,
        SPECIAL_ENTRY
    }

    /** The first offending member and the reason. */
    public record Violation(String member, Reason reason) {
    }

    private ArchiveMemberValidator() {
    }

    /** The first member that must not be extracted, or empty when all are safe. */
    public static Optional<Violation> check(List<ArchiveMember> members) {
        Objects.requireNonNull(members, "members");
        for (ArchiveMember member : members) {
            Optional<Reason> reason = check(member);
            if (reason.isPresent()) {
                return Optional.of(new Violation(member.name(), reason.get()));
            }
        }
        return Optional.empty();
    }

    /** Why one member must not be extracted, or empty when it is safe. */
    public static Optional<Reason> check(ArchiveMember member) {
        Objects.requireNonNull(member, "member");
        Optional<Reason> nameProblem = checkPath(member.name());
        if (nameProblem.isPresent()) {
            return nameProblem;
        }
        return switch (member.type()) {
            case SPECIAL -> Optional.of(Reason.SPECIAL_ENTRY);
            case SYMLINK -> checkSymlink(member.name(), member.linkTarget());
            case HARDLINK -> member.linkTarget() == null || checkPath(member.linkTarget()).isPresent()
                || normalise(List.of(), member.linkTarget()).isEmpty()
                ? Optional.of(Reason.LINK_ESCAPE) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static Optional<Reason> checkPath(String path) {
        if (path.isEmpty()) {
            return Optional.of(Reason.EMPTY_NAME);
        }
        if (hasControlCharacter(path)) {
            return Optional.of(Reason.CONTROL_CHARACTER);
        }
        if (path.charAt(0) == '/' || path.charAt(0) == '\\') {
            return Optional.of(Reason.ABSOLUTE_PATH);
        }
        if (hasDriveLetter(path)) {
            return Optional.of(Reason.DRIVE_LETTER);
        }
        if (normalise(List.of(), path) == null) {
            return Optional.of(Reason.PARENT_TRAVERSAL);
        }
        return Optional.empty();
    }

    private static Optional<Reason> checkSymlink(String name, String target) {
        if (target == null) {
            // the listing does not show link targets; the check after extraction covers this link
            return Optional.empty();
        }
        if (target.isEmpty()) {
            return Optional.of(Reason.LINK_ESCAPE);
        }
        if (hasControlCharacter(target)) {
            return Optional.of(Reason.CONTROL_CHARACTER);
        }
        if (target.charAt(0) == '/' || target.charAt(0) == '\\' || hasDriveLetter(target)) {
            return Optional.of(Reason.LINK_ESCAPE);
        }
        List<String> segments = normalise(List.of(), name);
        List<String> parent = segments.isEmpty() ? segments : segments.subList(0, segments.size() - 1);
        return normalise(parent, target) == null ? Optional.of(Reason.LINK_ESCAPE) : Optional.empty();
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDriveLetter(String value) {
        return value.length() >= 2 && value.charAt(1) == ':' && Character.isLetter(value.charAt(0));
    }

    /**
     * Resolves {@code path} lexically against {@code base} (segments below the root), with both
     * {@code /} and {@code \} as separators.
     *
     * @return the resulting segments, or null when the path climbs above the root
     */
    static List<String> normalise(List<String> base, String path) {
        List<String> segments = new ArrayList<>(base);
        for (String segment : path.split("[/\\\\]")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        return segments;
    }
}
