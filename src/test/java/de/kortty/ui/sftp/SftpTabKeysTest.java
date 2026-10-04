package de.kortty.ui.sftp;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** One SFTP tab per connection for standalone tabs, one per terminal pane for borrowed ones (D9). */
class SftpTabKeysTest {

    @Test
    void borrowedAndConnectionKeysDoNotCollide() {
        ServerConnection connection = connection("abc");
        String standalone = SftpTabKeys.standalone(connection);
        String borrowed = SftpTabKeys.borrowed("abc", "terminal-1");

        assertThat(standalone).isNotNull();
        assertThat(standalone).isNotEqualTo(borrowed);
        assertThat(SftpTabKeys.isBorrowed(borrowed)).isTrue();
        assertThat(SftpTabKeys.isBorrowed(standalone)).isFalse();
    }

    @Test
    void twoPanesOfOneTabGiveTwoTabs() {
        assertThat(SftpTabKeys.borrowed("tab", "terminal-1")).isNotEqualTo(SftpTabKeys.borrowed("tab", "terminal-2"));
        assertThat(SftpTabKeys.borrowed("tab-a", "terminal-1")).isNotEqualTo(SftpTabKeys.borrowed("tab-b", "terminal-1"));
        assertThat(SftpTabKeys.borrowed("tab", "terminal-1")).isEqualTo(SftpTabKeys.borrowed("tab", "terminal-1"));
    }

    @Test
    void theSameConnectionGivesTheSameStandaloneKey() {
        assertThat(SftpTabKeys.standalone(connection("abc"))).isEqualTo(SftpTabKeys.standalone(connection("abc")));
        assertThat(SftpTabKeys.standalone(connection("abc"))).isNotEqualTo(SftpTabKeys.standalone(connection("xyz")));
    }

    @Test
    void aConnectionWithoutAnIdIsNeverReused() {
        assertThat(SftpTabKeys.standalone(connection(null))).isNull();
        assertThat(SftpTabKeys.standalone(null)).isNull();
    }

    @Test
    void aBorrowedKeyNeedsBothIds() {
        expectThrows(IllegalArgumentException.class, () -> SftpTabKeys.borrowed(null, "terminal-1"));
        expectThrows(IllegalArgumentException.class, () -> SftpTabKeys.borrowed("tab", " "));
    }

    @Test
    void mainWindowDedupesThroughTheKeys() throws Exception {
        String window = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertThat(window).contains("selectSftpTab(de.kortty.ui.sftp.SftpTabKeys.standalone(connection))");
        assertThat(window).contains("dedupeKey.equals(sftpTab.getDedupeKey())");
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertThat(view).contains("de.kortty.ui.sftp.SftpTabKeys.borrowed(terminalViewId,");
    }

    private static ServerConnection connection(String id) {
        ServerConnection connection = new ServerConnection("c", "srv.example", 22, "alice");
        connection.setId(id);
        return connection;
    }
}
