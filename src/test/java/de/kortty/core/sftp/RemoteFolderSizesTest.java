package de.kortty.core.sftp;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class RemoteFolderSizesTest {

    @Test
    void everyPathIsQuotedAfterTheEndOfOptions() {
        assertThat(RemoteFolderSizes.command(List.of("/home/demo/backups", "/srv/it's here", "-rf")))
            .isEqualTo("'du' '-sk' '--' '/home/demo/backups' '/srv/it'\\''s here' '-rf'");
        assertThrows(IllegalArgumentException.class, () -> RemoteFolderSizes.command(List.of()));
    }

    @Test
    void totalsBecomeBytesForTheRequestedFoldersOnly() {
        String output = "1200\t/home/demo/backups\n"
            + "8\t/home/demo/www/\n"
            + "99\t/etc\n"
            + "du: cannot read directory '/home/demo/backups/secret': Permission denied\n"
            + "garbage\n";

        var sizes = RemoteFolderSizes.parse(output, List.of("/home/demo/backups", "/home/demo/www"));

        assertThat(sizes).containsExactly("/home/demo/backups", 1200L * 1024, "/home/demo/www", 8L * 1024);
    }

    @Test
    void namesWithTabsOrSpacesKeepTheirWholePath() {
        var sizes = RemoteFolderSizes.parse("4\t/data/two words\n", List.of("/data/two words"));
        assertThat(sizes).containsExactly("/data/two words", 4096L);
        assertThat(RemoteFolderSizes.parse("", List.of("/x"))).isEmpty();
        assertThat(RemoteFolderSizes.parse(null, List.of("/x"))).isEmpty();
    }
}
