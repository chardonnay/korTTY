package de.kortty.core.remote.extract;

import java.util.Objects;

/**
 * One entry of an archive listing.
 *
 * @param name the member path as stored in the archive
 * @param type what kind of entry it is; {@link Type#UNKNOWN} when the listing does not say
 * @param linkTarget the target of a symlink or hardlink, or null when not known
 */
public record ArchiveMember(String name, Type type, String linkTarget) {

    public enum Type {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        /** A device, FIFO or other special entry; never extracted. */
        SPECIAL,
        UNKNOWN
    }

    public ArchiveMember {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public static ArchiveMember of(String name) {
        return new ArchiveMember(name, Type.UNKNOWN, null);
    }
}
