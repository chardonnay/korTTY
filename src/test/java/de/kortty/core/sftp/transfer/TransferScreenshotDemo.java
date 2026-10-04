package de.kortty.core.sftp.transfer;

import java.nio.file.Path;
import java.util.List;

/**
 * A fixed set of transfer items for the manual's transfer list screenshot: a folder upload with a
 * running, a finished, a waiting and a failed file inside, a large download in progress, a finished
 * download and a waiting upload. Progress and speed are fed with fixed clock values, so every run
 * draws the same rows. Demo names only; nothing touches a file system or a server.
 */
public final class TransferScreenshotDemo {

    private static final long KB = 1024;
    private static final long MB = 1024 * KB;

    private TransferScreenshotDemo() {
    }

    /** The demo batches' items, top-level items and their contents, in queue order. */
    public static List<TransferItem> items() {
        Path local = Path.of("demo", "projects");
        TransferBatch upload = new TransferBatch(TransferDirection.UPLOAD, "/var/www", null);
        TransferItem site = TransferItem.upload(upload, null, TransferItem.Kind.FOLDER, local.resolve("website"),
            "/var/www", "website", -1);
        upload.add(site);
        TransferItem index = TransferItem.upload(upload, site, TransferItem.Kind.FILE,
            local.resolve("website/index.html"), "/var/www/website", "index.html", 48 * KB);
        TransferItem app = TransferItem.upload(upload, site, TransferItem.Kind.FILE,
            local.resolve("website/app.js"), "/var/www/website", "app.js", 1_250 * KB);
        TransferItem logo = TransferItem.upload(upload, site, TransferItem.Kind.FILE,
            local.resolve("website/logo.png"), "/var/www/website", "logo.png", 320 * KB);
        TransferItem favicon = TransferItem.upload(upload, site, TransferItem.Kind.FILE,
            local.resolve("website/favicon.ico"), "/var/www/website", "favicon.ico", 15 * KB);
        site.addChildren(List.of(index, app, favicon, logo));
        site.setExpanded(true);
        site.setState(TransferState.RUNNING);
        done(index, 48 * KB);
        running(app, 1_250 * KB, 770 * KB, 860 * KB);
        favicon.fail("Permission denied", false);
        TransferItem notes = TransferItem.upload(upload, null, TransferItem.Kind.FILE,
            local.resolve("release-notes.pdf"), "/var/www", "release-notes.pdf", 2_400 * KB);
        upload.add(notes);

        TransferBatch download = new TransferBatch(TransferDirection.DOWNLOAD, "/srv/backup", null);
        TransferItem backup = TransferItem.download(download, null, TransferItem.Kind.FILE,
            "/srv/backup/backup-2026-10-01.tar.gz", local, "backup-2026-10-01.tar.gz", 840 * MB);
        download.add(backup);
        running(backup, 840 * MB, 312 * MB, 4 * MB + 200 * KB);
        TransferItem log = TransferItem.download(download, null, TransferItem.Kind.FILE,
            "/srv/backup/access.log", local, "access.log", 12_600 * KB);
        download.add(log);
        done(log, 12_600 * KB);

        return List.of(site, index, app, favicon, logo, backup, log, notes);
    }

    private static void done(TransferItem item, long size) {
        item.progress(size, size, 1_000_000_000L);
        item.finish(TransferState.DONE, null);
    }

    private static void running(TransferItem item, long size, long done, long bytesPerSecond) {
        item.setState(TransferState.RUNNING);
        long start = 1_000_000_000L;
        item.progress(done - bytesPerSecond, size, start);
        item.progress(done, size, start + 1_000_000_000L);
    }
}
