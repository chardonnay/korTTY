package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Where the saved terminal output (Settings › Window › Session Restore) is written, kept and
 * deleted: wiring in classes that need a running korTTY, pinned on their source. CRLF-safe.
 */
class SessionScrollbackWiringTest {

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }

    @Test
    void onlyTheKorttyThatWritesTheSnapshotWritesOrDeletesOutputFiles() throws IOException {
        String start = region(source("src/main/java/de/kortty/ui/MainWindow.java"),
            "private static void startSessionScrollback(", "sessionScrollback.start();");
        assertWithMessage("a second korTTY that only reads the snapshot must not touch the files")
            .that(start).contains("() -> writesAllowed.getAsBoolean() && store.canWrite()");
    }

    @Test
    void theLastOutputIsWrittenAfterTheSnapshotThatNamesIt() throws IOException {
        String shutdown = source("src/main/java/de/kortty/ui/MainWindow.java");
        int seal = shutdown.indexOf("sessionAutosave.saveAndSeal();");
        int flush = shutdown.indexOf("sessionScrollback.flushForExit();");
        assertWithMessage("saveAndSeal is called").that(seal).isAtLeast(0);
        assertWithMessage("the output is flushed after the snapshot named its files").that(flush).isGreaterThan(seal);
    }

    @Test
    void aKeyChangeABackupRestoreAndTurningItOffDeleteTheFiles() throws IOException {
        String master = source("src/main/java/de/kortty/security/MasterPasswordManager.java");
        assertWithMessage("a committed password change purges")
            .that(region(master, "public void commitPasswordChange(", "\n    }\n")).contains("purgeSessionScrollback();");
        assertWithMessage("a new master password (new salt) purges")
            .that(region(master, "public void setupPassword(", "\n    }\n")).contains("purgeSessionScrollback();");
        assertWithMessage("a backup restore purges")
            .that(source("src/main/java/de/kortty/core/BackupManager.java")).contains("SessionScrollbackStore.purge(configDir);");
        String dialog = source("src/main/java/de/kortty/ui/SettingsDialog.java");
        assertWithMessage("turning the setting off reaches the coordinator")
            .that(dialog).contains("MainWindow.sessionRestoreScrollbackChanged(sessionRestoreScrollbackCheck.isSelected());");
        String coordinator = region(source("src/main/java/de/kortty/ui/SessionScrollbackCoordinator.java"),
            "void settingChanged(boolean enabled) {", "\n    }\n");
        assertWithMessage("off purges every file").that(coordinator).contains("submit(store::purge);");
    }
}
