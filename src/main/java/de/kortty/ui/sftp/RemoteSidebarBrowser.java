package de.kortty.ui.sftp;

import de.kortty.core.SFTPSession;
import de.kortty.core.RemotePathSupport;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The listing engine of the terminal's remote files sidebar, JavaFX-free. It keeps one SFTP
 * session of its own on the followed pane's SSH session (borrowed, see {@link SFTPSession#attach}),
 * opened lazily by the first listing and closed by {@link #release()} when the sidebar is hidden
 * or the tab closes. Listings run one at a time on a single worker thread; a generation counter
 * drops a result a newer request overtook, so a burst of {@code cd}s never shows an old folder last.
 *
 * <p>A folder that cannot be read (gone, not a folder, no permission) keeps the previous listing:
 * the result is a {@link Failure}, never a dialog. Symbolic links are listed as the server reports
 * them and never followed while listing.
 */
public final class RemoteSidebarBrowser implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RemoteSidebarBrowser.class);

    /** Attaches an SFTP session to a pane's SSH session. Runs on the worker thread. */
    @FunctionalInterface
    public interface SessionOpener {
        SFTPSession open() throws IOException;
    }

    /** What a listing request produced. */
    public sealed interface Result permits Listing, Failure {
        long generation();

        String paneKey();
    }

    /**
     * A listed folder.
     *
     * @param path  the absolute folder listed
     * @param items its entries, the parent entry first, sorted like the SFTP manager's Type column
     */
    public record Listing(long generation, String paneKey, String path, List<SftpFileItem> items) implements Result {
        public Listing {
            items = List.copyOf(items);
        }
    }

    /** Why a listing failed. */
    public enum FailureKind {
        /** The folder does not exist or is no folder. */
        NOT_FOUND,
        /** The server refused to list it. */
        DENIED,
        /** The pane's session is gone or refuses SFTP. */
        UNAVAILABLE,
        /** Anything else. */
        ERROR
    }

    /**
     * A folder that could not be listed; the sidebar keeps what it shows.
     *
     * @param path    the folder asked for, or null for the login folder
     * @param message a short reason for the status line
     */
    public record Failure(long generation, String paneKey, @Nullable String path, FailureKind kind, String message)
            implements Result {
    }

    private final Consumer<Result> results;
    private final Executor deliver;
    private final Runnable onDisconnected;
    private final ExecutorService worker;
    private final AtomicLong generation = new AtomicLong();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    // Written on the worker thread only; read from others through the volatile.
    private volatile @Nullable SFTPSession session;
    private volatile @Nullable String sessionPane;
    private volatile boolean closed;

    /**
     * @param results        receives every result that was not overtaken, through {@code deliver}
     * @param deliver        where results are handed over, e.g. {@code Platform::runLater}
     * @param onDisconnected called through {@code deliver} when the sidebar's SFTP session is lost
     */
    public RemoteSidebarBrowser(Consumer<Result> results, Executor deliver, Runnable onDisconnected) {
        this.results = Objects.requireNonNull(results, "results");
        this.deliver = Objects.requireNonNull(deliver, "deliver");
        this.onDisconnected = onDisconnected != null ? onDisconnected : () -> { };
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SFTP-Sidebar");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Lists {@code path} of the pane {@code paneKey}, attaching through {@code opener} when the
     * sidebar has no session on that pane yet. Never blocks.
     *
     * @param path the folder, or null for the session's login folder
     * @return the request's generation
     */
    public long list(String paneKey, SessionOpener opener, @Nullable String path) {
        Objects.requireNonNull(paneKey, "paneKey");
        Objects.requireNonNull(opener, "opener");
        long requested = generation.incrementAndGet();
        if (closed) {
            return requested;
        }
        try {
            worker.execute(() -> runListing(requested, paneKey, opener, path));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("Sidebar listing after close ignored");
        }
        return requested;
    }

    /** Closes the sidebar's SFTP session (the sidebar was hidden); a later listing attaches again. */
    public void release() {
        generation.incrementAndGet();
        if (closed) {
            return;
        }
        try {
            worker.execute(this::closeSession);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("Sidebar release after close ignored");
        }
    }

    /** The session the sidebar lists with, while it is open; for transfers and drags. */
    public @Nullable SFTPSession session() {
        SFTPSession current = session;
        return current != null && current.isConnected() ? current : null;
    }

    /** The pane the open session belongs to, or null. */
    public @Nullable String sessionPane() {
        return sessionPane;
    }

    /** The generation of the newest request. */
    public long generation() {
        return generation.get();
    }

    /** Closes the session and stops the worker; waits briefly for it. Not on the FX thread when it matters. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        generation.incrementAndGet();
        try {
            worker.execute(this::closeSession);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Already shut down.
        }
        worker.shutdown();
    }

    /** Waits until the worker stopped after {@link #close()}; for tests and orderly shutdown. */
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return worker.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ worker

    private void runListing(long requested, String paneKey, SessionOpener opener, @Nullable String path) {
        if (requested != generation.get()) {
            return;
        }
        SFTPSession current;
        try {
            current = sessionFor(paneKey, opener);
        } catch (IOException | RuntimeException e) {
            logger.info("Remote files sidebar could not open SFTP on the terminal session: {}", e.getMessage());
            publish(new Failure(requested, paneKey, path, FailureKind.UNAVAILABLE, messageOf(e)));
            return;
        }
        try {
            String absolute = resolveFolder(path, current.getCurrentDirectory());
            SftpClient.Attributes attributes = current.getAttributes(absolute);
            if (!attributes.isDirectory()) {
                publish(new Failure(requested, paneKey, absolute, FailureKind.NOT_FOUND, absolute));
                return;
            }
            List<SftpFileItem> items = new ArrayList<>();
            if (!"/".equals(absolute)) {
                items.add(SftpFileItem.parent(RemotePathSupport.parentRemotePath(absolute)));
            }
            for (SftpClient.DirEntry entry : current.listFiles(absolute)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                items.add(SftpFileItem.fromRemoteEntry(absolute, entry, dateFormat));
            }
            items.sort(SftpFileItemComparators.type(true));
            publish(new Listing(requested, paneKey, absolute, items));
        } catch (SftpException e) {
            FailureKind kind = switch (e.getStatus()) {
                case SftpConstants.SSH_FX_NO_SUCH_FILE, SftpConstants.SSH_FX_NO_SUCH_PATH,
                     SftpConstants.SSH_FX_NOT_A_DIRECTORY -> FailureKind.NOT_FOUND;
                case SftpConstants.SSH_FX_PERMISSION_DENIED -> FailureKind.DENIED;
                default -> FailureKind.ERROR;
            };
            publish(new Failure(requested, paneKey, path, kind, messageOf(e)));
        } catch (IOException | RuntimeException e) {
            boolean lost = !current.isConnected();
            publish(new Failure(requested, paneKey, path, lost ? FailureKind.UNAVAILABLE : FailureKind.ERROR,
                messageOf(e)));
        }
    }

    /**
     * The absolute folder for {@code path}: {@code ~}, {@code ~/x} and relative paths against the
     * login folder, {@code .} and {@code ..} resolved by text; null is the login folder.
     */
    static String resolveFolder(@Nullable String path, String loginFolder) {
        String resolved = RemotePathSupport.resolveTargetDirectory(path, loginFolder);
        if (!resolved.startsWith("/") && loginFolder != null && loginFolder.startsWith("/")) {
            resolved = RemotePathSupport.appendRemotePath(loginFolder, resolved);
        }
        return RemotePathSupport.normalizeAbsolutePath(resolved);
    }

    private SFTPSession sessionFor(String paneKey, SessionOpener opener) throws IOException {
        SFTPSession current = session;
        if (current != null && paneKey.equals(sessionPane) && current.isConnected()) {
            return current;
        }
        closeSession();
        SFTPSession opened = opener.open();
        if (opened == null) {
            throw new IOException("no session");
        }
        opened.setDisconnectListener(() -> {
            if (session == opened && !closed) {
                deliver.execute(onDisconnected);
            }
        });
        session = opened;
        sessionPane = paneKey;
        return opened;
    }

    private void closeSession() {
        SFTPSession current = session;
        session = null;
        sessionPane = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException e) {
                logger.debug("Closing the sidebar's SFTP session failed: {}", e.getMessage());
            }
        }
    }

    private void publish(Result result) {
        if (result.generation() != generation.get() || closed) {
            return;
        }
        deliver.execute(() -> {
            // Checked again where the result lands: a newer request may have started meanwhile.
            if (result.generation() == generation.get() && !closed) {
                results.accept(result);
            }
        });
    }

    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
