package de.kortty.core.remote.search;

import de.kortty.core.remote.RemoteCommandCancellation;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The search for servers without {@code find} or without exec channels: a breadth-first walk over
 * SFTP. Symlinks are never followed (a folder is only entered when {@code lstat} says it is a real
 * folder), so a symlink loop cannot trap it. The depth, the number of entries read, the result
 * count and the time are capped, and a cancel is noticed before the next entry. Names are matched
 * on the client with the request's name filter, ignoring case like the folder filter.
 */
public final class SftpWalkStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SftpWalkStrategy.class);

    private final RemoteTreeReader reader;
    private final LongSupplier nanoClock;

    public SftpWalkStrategy(RemoteTreeReader reader) {
        this(reader, System::nanoTime);
    }

    SftpWalkStrategy(RemoteTreeReader reader, LongSupplier nanoClock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    private record Folder(String path, int depth) { }

    /**
     * Walks below the request's root and hands matches to {@code batches} in groups of 100.
     *
     * @throws IOException when the root itself cannot be read
     */
    public RemoteSearchOutcome search(RemoteSearchRequest request, Consumer<List<RemoteSearchHit>> batches,
                                      RemoteCommandCancellation cancellation) throws IOException {
        HitBatcher batcher = new HitBatcher(batches);
        RemoteSearchOutcome.StopReason stop = walk(request, batcher, cancellation);
        batcher.flush();
        return new RemoteSearchOutcome(RemoteSearchOutcome.Strategy.SFTP_WALK, batcher.count(), stop);
    }

    private RemoteSearchOutcome.StopReason walk(RemoteSearchRequest request, HitBatcher batcher,
                                                RemoteCommandCancellation cancellation) throws IOException {
        long deadline = nanoClock.getAsLong() + request.timeout().toNanos();
        long entries = 0;
        Deque<Folder> queue = new ArrayDeque<>();
        queue.add(new Folder(request.root(), 0));
        boolean first = true;
        while (!queue.isEmpty()) {
            if (cancellation.isCancelled()) {
                return RemoteSearchOutcome.StopReason.CANCELLED;
            }
            if (nanoClock.getAsLong() - deadline >= 0) {
                return RemoteSearchOutcome.StopReason.TIME_LIMIT;
            }
            Folder folder = queue.poll();
            List<SftpClient.DirEntry> children;
            try {
                children = reader.list(folder.path());
            } catch (IOException e) {
                if (first) {
                    throw e;
                }
                // An unreadable subfolder is skipped, like find does.
                logger.debug("Search skips an unreadable folder: {}", e.getClass().getSimpleName());
                continue;
            } finally {
                first = false;
            }
            for (SftpClient.DirEntry child : children) {
                if (cancellation.isCancelled()) {
                    return RemoteSearchOutcome.StopReason.CANCELLED;
                }
                String name = child.getFilename();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                        || name.indexOf('/') >= 0) {
                    continue;
                }
                if (++entries > request.maxEntries()) {
                    return RemoteSearchOutcome.StopReason.ENTRY_LIMIT;
                }
                if ((entries & 0xFF) == 0 && nanoClock.getAsLong() - deadline >= 0) {
                    return RemoteSearchOutcome.StopReason.TIME_LIMIT;
                }
                String path = "/".equals(folder.path()) ? "/" + name : folder.path() + "/" + name;
                RemoteSearchHit.Kind kind = kindOf(child.getAttributes());
                boolean descend = false;
                if (kind == RemoteSearchHit.Kind.DIRECTORY && folder.depth() + 1 < request.maxDepth()) {
                    // readdir attributes are lstat-like on OpenSSH but not guaranteed to be: confirm.
                    kind = confirmedKind(path, kind);
                    descend = kind == RemoteSearchHit.Kind.DIRECTORY;
                }
                if (request.nameMatcher().test(name)) {
                    batcher.add(RemoteSearchHit.of(request.root(), path, kind));
                    if (batcher.count() >= request.maxResults()) {
                        return RemoteSearchOutcome.StopReason.RESULT_LIMIT;
                    }
                }
                if (descend) {
                    queue.add(new Folder(path, folder.depth() + 1));
                }
            }
        }
        return RemoteSearchOutcome.StopReason.COMPLETED;
    }

    private RemoteSearchHit.Kind confirmedKind(String path, RemoteSearchHit.Kind listed) {
        try {
            return kindOf(reader.lstat(path));
        } catch (IOException e) {
            // Cannot tell: do not enter it.
            return listed == RemoteSearchHit.Kind.DIRECTORY ? RemoteSearchHit.Kind.OTHER : listed;
        }
    }

    static RemoteSearchHit.Kind kindOf(SftpClient.Attributes attributes) {
        if (attributes == null) {
            return RemoteSearchHit.Kind.UNKNOWN;
        }
        if (attributes.isSymbolicLink()) {
            return RemoteSearchHit.Kind.SYMLINK;
        }
        if (attributes.isDirectory()) {
            return RemoteSearchHit.Kind.DIRECTORY;
        }
        if (attributes.isRegularFile()) {
            return RemoteSearchHit.Kind.FILE;
        }
        return RemoteSearchHit.Kind.OTHER;
    }
}
