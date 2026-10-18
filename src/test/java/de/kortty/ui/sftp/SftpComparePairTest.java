package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SftpComparePairTest {

    private static SftpFileItem file(String name) {
        return SftpFileItem.fromDetails(name, "/x/" + name, true, "1 B", "", "", "", "", 1);
    }

    private static SftpFileItem dir(String name) {
        return SftpFileItem.fromDetails(name, "/x/" + name, false, "—", "", "", "", "", -1);
    }

    private static final SftpFileItem PARENT = SftpFileItem.parent("/");

    @Test
    void twoFilesInTheActivePanel() {
        SftpComparePair pair = SftpComparePair.choose(List.of(file("a.conf"), file("b.conf")), List.of(file("x")), false);

        assertThat(pair.kind()).isEqualTo(SftpComparePair.Kind.FILES);
        assertThat(pair.left().item().getName()).isEqualTo("a.conf");
        assertThat(pair.right().item().getName()).isEqualTo("b.conf");
        assertThat(pair.left().side()).isEqualTo(SftpComparePair.Side.LOCAL);
        assertThat(pair.right().side()).isEqualTo(SftpComparePair.Side.LOCAL);
    }

    @Test
    void oneInEachPanelPutsTheLocalOneLeft() {
        SftpComparePair pair = SftpComparePair.choose(List.of(dir("site")), List.of(dir("www")), true);

        assertThat(pair.kind()).isEqualTo(SftpComparePair.Kind.FOLDERS);
        assertThat(pair.left().side()).isEqualTo(SftpComparePair.Side.LOCAL);
        assertThat(pair.right().isRemote()).isTrue();
    }

    @Test
    void theActivePanelWinsOverOneInEach() {
        // Two on the server (active) and one locally: the server pair is meant.
        SftpComparePair pair = SftpComparePair.choose(List.of(file("l")), List.of(file("r1"), file("r2")), true);

        assertThat(pair.left().item().getName()).isEqualTo("r1");
        assertThat(pair.right().item().getName()).isEqualTo("r2");
    }

    @Test
    void theParentEntryDoesNotCountAndAFileWithAFolderIsMixed() {
        assertThat(SftpComparePair.choose(List.of(PARENT, file("a")), List.of(file("b")), false).kind())
            .isEqualTo(SftpComparePair.Kind.FILES);
        assertThat(SftpComparePair.choose(List.of(file("a")), List.of(dir("b")), false).kind())
            .isEqualTo(SftpComparePair.Kind.MIXED);
        assertThat(SftpComparePair.choose(List.of(file("a"), file("b"), file("c")), List.of(), false).kind())
            .isEqualTo(SftpComparePair.Kind.NONE);
        assertThat(SftpComparePair.choose(List.of(), List.of(), true).kind()).isEqualTo(SftpComparePair.Kind.NONE);
        assertThat(SftpComparePair.choose(null, null, true).left()).isNull();
    }

    @Test
    void textIsUtf8OrLatin1AndBinaryIsRefused() {
        assertThat(SftpTextContent.decode("Größe=1\n".getBytes(StandardCharsets.UTF_8))).hasValue("Größe=1\n");
        // A byte order mark is not part of the text.
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'};
        assertThat(SftpTextContent.decode(bom)).hasValue("a");
        // An older configuration file in Latin-1 is not valid UTF-8.
        assertThat(SftpTextContent.decode("Größe".getBytes(StandardCharsets.ISO_8859_1))).hasValue("Größe");
        assertThat(SftpTextContent.decode(new byte[] {'E', 'L', 'F', 0, 1})).isEmpty();
        assertThat(SftpTextContent.decode(new byte[0])).hasValue("");
        assertThat(SftpTextContent.decode(null)).isEmpty();
    }

    @Test
    void aNewFileIsProposedInTheRightFilesPlace() {
        SftpNewFilePlacement.Ref local = new SftpNewFilePlacement.Ref(SftpComparePair.Side.LOCAL, "/home/me/sshd_config");
        SftpNewFilePlacement.Ref server = new SftpNewFilePlacement.Ref(SftpComparePair.Side.REMOTE, "/etc/ssh/sshd_config");

        // Local on the left, the server file on the right: the new file replaces the server one.
        assertThat(SftpNewFilePlacement.proposedSide(local, server, true)).isEqualTo(SftpComparePair.Side.REMOTE);
        assertThat(SftpNewFilePlacement.proposedPath(SftpComparePair.Side.REMOTE, local, server, "/srv"))
            .isEqualTo("/etc/ssh/sshd_config");
        assertThat(SftpNewFilePlacement.proposedPath(SftpComparePair.Side.LOCAL, local, server, "/tmp"))
            .isEqualTo("/home/me/sshd_config");
        // A server that may not be written is not proposed.
        assertThat(SftpNewFilePlacement.proposedSide(local, server, false)).isEqualTo(SftpComparePair.Side.LOCAL);

        // Two local files: the server gets the right file's name in the folder its panel shows.
        SftpNewFilePlacement.Ref other = new SftpNewFilePlacement.Ref(SftpComparePair.Side.LOCAL, "/home/me/new/app.conf");
        assertThat(SftpNewFilePlacement.proposedSide(local, other, true)).isEqualTo(SftpComparePair.Side.LOCAL);
        assertThat(SftpNewFilePlacement.proposedPath(SftpComparePair.Side.REMOTE, local, other, "/srv/app"))
            .isEqualTo("/srv/app/app.conf");
        assertThat(SftpNewFilePlacement.proposedPath(SftpComparePair.Side.REMOTE, local, other, null))
            .isEqualTo("app.conf");
        assertThat(SftpNewFilePlacement.backupPath("/etc/ssh/sshd_config")).isEqualTo("/etc/ssh/sshd_config.bak");
    }
}
