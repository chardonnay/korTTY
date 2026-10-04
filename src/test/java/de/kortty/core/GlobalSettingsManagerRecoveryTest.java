package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class GlobalSettingsManagerRecoveryTest {

    @Test
    void rawBelInsideASettingIsRemovedAndTheOtherSettingsSurvive() throws Exception {
        Path dir = Files.createTempDirectory("kortty-settings-recovery");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.getSettings().setSnippetAnalysisHistoryMaxSize(12);
            manager.getSettings().setQuickConnectExpandedSections(List.of("before\u0007after", "ai"));
            manager.save();
            Path file = dir.resolve(GlobalSettingsManager.SETTINGS_FILE);
            byte[] written = Files.readAllBytes(file);
            // JAXB writes the BEL raw; this is what makes the file unreadable in the first place.
            assertThat(new String(written, StandardCharsets.UTF_8)).contains("before\u0007after");

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();

            GlobalSettings settings = reloaded.getSettings();
            assertThat(settings.getSnippetAnalysisHistoryMaxSize()).isEqualTo(12);
            assertThat(settings.getQuickConnectExpandedSections()).containsExactly("beforeafter", "ai");
            GlobalSettingsManager.LoadRecovery recovery = reloaded.getLoadRecovery().orElseThrow();
            assertThat(recovery.outcome()).isEqualTo(GlobalSettingsManager.LoadRecovery.Outcome.RECOVERED);
            assertThat(recovery.removedCharacters()).isEqualTo(1);
            assertThat(Files.readAllBytes(recovery.backup())).isEqualTo(written);
            assertThat(recovery.backup().getFileName().toString())
                .startsWith(GlobalSettingsManager.SETTINGS_FILE + ".unreadable-");

            reloaded.save();
            GlobalSettingsManager third = new GlobalSettingsManager(dir);
            third.load();
            assertThat(third.getLoadRecovery()).isEmpty();
            assertThat(third.getSettings().getSnippetAnalysisHistoryMaxSize()).isEqualTo(12);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void trulyBrokenFileFallsBackToDefaultsAndKeepsItsBackup() throws Exception {
        Path dir = Files.createTempDirectory("kortty-settings-broken");
        try {
            Path file = dir.resolve(GlobalSettingsManager.SETTINGS_FILE);
            byte[] broken = "<?xml version=\"1.0\"?>\n<globalSettings><language>de</lang".getBytes(StandardCharsets.UTF_8);
            Files.write(file, broken);

            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();

            GlobalSettingsManager.LoadRecovery recovery = manager.getLoadRecovery().orElseThrow();
            assertThat(recovery.outcome()).isEqualTo(GlobalSettingsManager.LoadRecovery.Outcome.RESET);
            assertThat(recovery.backup()).isNotNull();
            assertThat(manager.getSettings().getSnippetAnalysisHistoryMaxSize()).isEqualTo(5);

            manager.save();
            assertThat(Files.readAllBytes(recovery.backup())).isEqualTo(broken);
            assertThat(Files.readAllBytes(file)).isNotEqualTo(broken);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void onlyTheNewestBackupsAreKept() throws Exception {
        Path dir = Files.createTempDirectory("kortty-settings-prune");
        try {
            Path file = dir.resolve(GlobalSettingsManager.SETTINGS_FILE);
            Files.writeString(file, "not xml");
            for (int i = 0; i < 3; i++) {
                Files.writeString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE + ".unreadable-2000010" + i + "-000000"), "old");
            }
            Path newest = null;
            for (int i = 0; i < UnreadableSettingsBackup.KEEP; i++) {
                newest = UnreadableSettingsBackup.copyAside(file);
            }

            List<Path> backups = UnreadableSettingsBackup.backups(file);
            assertThat(backups).hasSize(UnreadableSettingsBackup.KEEP);
            assertThat(backups).contains(newest);
            assertThat(backups.stream().map(p -> p.getFileName().toString()).anyMatch(n -> n.contains("2000010")))
                .isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void savingIsRefusedWhenDefaultsReplacedAFileThatCouldNotBeCopied() throws Exception {
        Path dir = Files.createTempDirectory("kortty-settings-readonly");
        Path file = dir.resolve(GlobalSettingsManager.SETTINGS_FILE);
        try {
            Files.writeString(file, "not xml");
            if (!dir.toFile().setWritable(false)) {
                return; // file system without permission bits
            }
            if (Files.isWritable(dir)) {
                return; // running as root
            }
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();
            assertThat(manager.getLoadRecovery().orElseThrow().backup()).isNull();
            dir.toFile().setWritable(true);

            assertThrows(IOException.class, manager::save);
            assertThat(Files.readString(file)).isEqualTo("not xml");
        } finally {
            dir.toFile().setWritable(true);
            deleteRecursively(dir);
        }
    }

    @Test
    void sanitizerRemovesEveryCharacterXml10CannotHold() {
        String text = "a\u0000b\tc\u001Fd￾e￿f\uD800g\uDC00h😀i\r\n";
        XmlCharacterSanitizer.Result result = XmlCharacterSanitizer.sanitize(
            text.getBytes(StandardCharsets.UTF_8));
        // getBytes turns each lone surrogate into "?"; the encoded-surrogate bytes are tested below.
        assertThat(result.text()).isEqualTo("ab\tcde" + "f?g?h😀i\r\n");
        assertThat(result.removedCount()).isEqualTo(4);

        byte[] malformed = {'x', (byte) 0xC3, 'y', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'z'};
        XmlCharacterSanitizer.Result bytes = XmlCharacterSanitizer.sanitize(malformed);
        assertThat(bytes.text()).isEqualTo("xyz");
        assertThat(bytes.removedCount()).isAtLeast(2);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
