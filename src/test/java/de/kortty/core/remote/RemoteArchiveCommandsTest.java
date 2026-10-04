package de.kortty.core.remote;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class RemoteArchiveCommandsTest {

    private static final Path SFTP_TAB = Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java");

    @Test
    public void noFormatAcceptsAPassword() {
        for (RemoteArchiveCommands.Format format : RemoteArchiveCommands.Format.values()) {
            assertThat(RemoteArchiveCommands.supportsPassword(format)).isFalse();
        }
    }

    @Test
    public void zipCommandQuotesEverythingAndCarriesNoPasswordOption() {
        String command = RemoteArchiveCommands.build(RemoteArchiveCommands.Format.ZIP,
            List.of("/srv/it's here", "/srv/$(id)"), "/tmp/out.zip", 6, List.of("*.log"));

        assertThat(command).isEqualTo("zip -r -6 '/tmp/out.zip' -x '*.log' '/srv/it'\\''s here' '/srv/$(id)'");
        assertThat(command).doesNotContain(" -P");
        assertThat(command).doesNotContain(" -e");
    }

    @Test
    public void tarAndSevenZipCommands() {
        assertThat(RemoteArchiveCommands.build(RemoteArchiveCommands.Format.TAR_BZ2,
            List.of("/a"), "/tmp/x.tar.bz2", 12, List.of("tmp")))
            .isEqualTo("BZIP2=-9 tar -cjf '/tmp/x.tar.bz2' '--exclude=tmp' '/a'");
        String sevenZip = RemoteArchiveCommands.build(RemoteArchiveCommands.Format.SEVEN_ZIP,
            List.of("/a"), "/tmp/x.7z", -1, null);
        assertThat(sevenZip).isEqualTo("\"$(command -v 7z || command -v 7za)\" a -mx=0 '/tmp/x.7z' '/a'");
        assertThat(sevenZip).doesNotContain("-p");
    }

    @Test
    public void relativePathsStartingWithADashCannotBecomeOptions() {
        String command = RemoteArchiveCommands.build(RemoteArchiveCommands.Format.ZIP,
            List.of("-rf"), "-out.zip", 1, List.of());

        assertThat(command).isEqualTo("zip -r -1 './-out.zip' './-rf'");
    }

    @Test
    public void sftpManagerNeverPutsAnArchivePasswordOnTheCommandLine() throws IOException {
        String source = Files.readString(SFTP_TAB, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf("private void showArchiveDialog(");
        int end = source.indexOf("private String formatSize(long bytes)");
        String remoteArchive = source.substring(start, end);

        assertThat(remoteArchive).contains("RemoteArchiveCommands.build(");
        assertThat(remoteArchive).contains("sftp.archive.passwordUnsupportedRemote");
        assertThat(remoteArchive).doesNotContain("PasswordField");
        assertThat(remoteArchive).doesNotContain("\" -P ");
        assertThat(remoteArchive).doesNotContain("-p'");
        assertThat(source).doesNotContain("buildArchiveCommand(");
    }
}
