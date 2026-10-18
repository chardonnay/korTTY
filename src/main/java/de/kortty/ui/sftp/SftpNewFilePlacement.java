package de.kortty.ui.sftp;

import de.kortty.core.RemotePathSupport;

import java.nio.file.Paths;
import java.util.Objects;

/**
 * Where the SFTP manager proposes to save a new file the AI wrote from two compared files. The
 * comparison runs from left to right, so the right file is the one the new file is meant to
 * replace: its side and path come first, then the left file's, then the folder its panel shows.
 */
public final class SftpNewFilePlacement {

    /** One compared file: its side and its path there. */
    public record Ref(SftpComparePair.Side side, String path) {
        public Ref {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(path, "path");
        }

        public String name() {
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    private SftpNewFilePlacement() {
    }

    /**
     * The side proposed first: the server when either compared file is there and the server may be
     * written, else this computer.
     */
    public static SftpComparePair.Side proposedSide(Ref left, Ref right, boolean serverWritable) {
        boolean onServer = right.side() == SftpComparePair.Side.REMOTE || left.side() == SftpComparePair.Side.REMOTE;
        return onServer && serverWritable ? SftpComparePair.Side.REMOTE : SftpComparePair.Side.LOCAL;
    }

    /**
     * The path proposed on {@code side}: the right file's when it is there, else the left file's,
     * else {@code folder} (the folder the panel of that side shows) with the right file's name.
     */
    public static String proposedPath(SftpComparePair.Side side, Ref left, Ref right, String folder) {
        if (right.side() == side) {
            return right.path();
        }
        if (left.side() == side) {
            return left.path();
        }
        if (folder == null || folder.isBlank()) {
            return right.name();
        }
        return side == SftpComparePair.Side.REMOTE
            ? RemotePathSupport.appendRemotePath(folder, right.name())
            : Paths.get(folder).resolve(right.name()).toString();
    }

    /** Where the file at {@code path} is kept before it is overwritten. */
    public static String backupPath(String path) {
        return path + ".bak";
    }
}
