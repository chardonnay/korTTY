package de.kortty.core.remote.extract;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** Listing parsers and the symlink check after extraction. */
public class ArchiveListingParserTest {

    private static final String ZIPINFO = """
        Archive:  t.zip
        Zip file size: 577 bytes, number of entries: 3
        lrwxr-xr-x  3.0 unx        4 bx stor 26-Oct-04 15:32 esc
        drwxr-xr-x  3.0 unx        0 bx stor 26-Oct-04 15:32 sub/
        -rw-r--r--  3.0 unx        3 tx stor 26-Oct-04 15:32 a b.txt
        3 files, 7 bytes uncompressed, 7 bytes compressed:  0.0%
        """;

    @Test
    public void zipNamesMustMatchTheMemberCount() {
        ArchiveListingParser.Listing listing = ArchiveListingParser.zip("esc\nsub/\na b.txt\n", ZIPINFO);
        assertThat(listing.encrypted()).isFalse();
        assertThat(listing.members().stream().map(ArchiveMember::name).toList())
            .containsExactly("esc", "sub/", "a b.txt").inOrder();
        // a name with a line break shows up as one line too many
        assertThrows(IllegalArgumentException.class,
            () -> ArchiveListingParser.zip("esc\nsub/\na\nb.txt\n", ZIPINFO));
    }

    @Test
    public void encryptedZipMembersAreDetected() {
        String info = """
            Archive:  e.zip
            Zip file size: 191 bytes, number of entries: 1
            -rw-r--r--  3.0 unx        3 TX stor 26-Oct-04 15:32 a.txt
            1 file, 3 bytes uncompressed, 3 bytes compressed:  0.0%
            """;
        assertThat(ArchiveListingParser.zip("a.txt\n", info).encrypted()).isTrue();
    }

    @Test
    public void gnuTarListingGivesTypesAndTargets() {
        String output = String.join("\n",
            "drwxr-xr-x user/group       0 2024-01-01 12:00 dir/",
            "-rw-r--r-- user/group     123 2024-01-01 12:00 dir/ with  spaces.txt",
            "lrwxrwxrwx user/group       0 2024-01-01 12:00 dir/link -> /etc",
            "hrw-r--r-- user/group       0 2024-01-01 12:00 dir/hard link to ../../etc/shadow",
            "-rw-r--r-- user/group       1 2024-01-01 12:00 new\\nline\\\\x\\303\\244",
            "crw-r--r-- root/root      1,3 2024-01-01 12:00 dev/null",
            "");
        List<ArchiveMember> members = ArchiveListingParser.gnuTar(output).members();
        assertThat(members).containsExactly(
            new ArchiveMember("dir/", ArchiveMember.Type.DIRECTORY, null),
            new ArchiveMember("dir/ with  spaces.txt", ArchiveMember.Type.FILE, null),
            new ArchiveMember("dir/link", ArchiveMember.Type.SYMLINK, "/etc"),
            new ArchiveMember("dir/hard", ArchiveMember.Type.HARDLINK, "../../etc/shadow"),
            new ArchiveMember("new\nline\\xä", ArchiveMember.Type.FILE, null),
            new ArchiveMember("dev/null", ArchiveMember.Type.SPECIAL, null)).inOrder();
        assertThat(ArchiveMemberValidator.check(members.subList(2, 3))).isPresent();
        assertThat(ArchiveMemberValidator.check(members.subList(3, 4))).isPresent();
        assertThat(ArchiveMemberValidator.check(members.subList(4, 5))).isPresent();
    }

    @Test
    public void ambiguousOrUnreadableTarLinesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ArchiveListingParser.gnuTar(
            "lrwxrwxrwx u/g 0 2024-01-01 12:00 a -> b -> c\n"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveListingParser.gnuTar("garbage\n"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveListingParser.unescape("bad\\q"));
    }

    @Test
    public void plainTarPairsNamesWithTypes() {
        ArchiveListingParser.Listing listing = ArchiveListingParser.plainTar("./\n./esc\n./h\n",
            "drwxr-xr-x  0 a b 0 Oct  4 15:32 ./\nlrwxr-xr-x  0 a b 0 Oct  4 15:32 ./esc -> /etc\n"
                + "hrw-r--r--  0 a b 0 Oct  4 15:32 ./h link to ./x\n");
        assertThat(listing.members()).containsExactly(
            new ArchiveMember("./", ArchiveMember.Type.DIRECTORY, null),
            new ArchiveMember("./esc", ArchiveMember.Type.SYMLINK, null),
            new ArchiveMember("./h", ArchiveMember.Type.HARDLINK, null)).inOrder();
        // without a known target the hardlink is refused
        assertThat(ArchiveMemberValidator.check(listing.members())).isPresent();
        assertThrows(IllegalArgumentException.class, () -> ArchiveListingParser.plainTar("a\nb\n", "-x\n"));
        // BusyBox: "-rw-r--r-- root/root 0 2026-10-04 13:38:56 ./h -> ./a" is a hardlink
        assertThat(ArchiveListingParser.plainTar("./h\n", "-rw-r--r-- root/root 0 2026-10-04 13:38:56 ./h -> ./a\n")
            .members()).containsExactly(new ArchiveMember("./h", ArchiveMember.Type.HARDLINK, null));
    }

    @Test
    public void sevenZipTechnicalListing() {
        String output = """
            Path = dir
            Folder = +
            Attributes = D_ drwxr-xr-x

            Path = dir/file.txt
            Folder = -
            Size = 3
            Attributes = A_ -rw-r--r--
            Encrypted = -

            Path = dir/link
            Folder = -
            Attributes = A_ -lrwxrwxrwx
            Encrypted = -
            Comment =
            """;
        ArchiveListingParser.Listing listing = ArchiveListingParser.sevenZip(output);
        assertThat(listing.encrypted()).isFalse();
        assertThat(listing.members()).containsExactly(
            new ArchiveMember("dir", ArchiveMember.Type.DIRECTORY, null),
            new ArchiveMember("dir/file.txt", ArchiveMember.Type.FILE, null),
            new ArchiveMember("dir/link", ArchiveMember.Type.SYMLINK, null)).inOrder();
        assertThat(ArchiveListingParser.sevenZip("Path = a\nEncrypted = +\n").encrypted()).isTrue();
        assertThrows(IllegalArgumentException.class,
            () -> ArchiveListingParser.sevenZip("Path = a\nb\nFolder = -\n"));
    }

    @Test
    public void linkPairsAreReadFromNulSeparatedOutput() {
        byte[] output = "/s/a\0../x\0/s/b c\0d\0".getBytes(StandardCharsets.UTF_8);
        assertThat(RemoteArchiveExtractor.parseLinkPairs(output))
            .containsExactly("/s/a", "../x", "/s/b c", "d").inOrder();
        assertThrows(IllegalArgumentException.class,
            () -> RemoteArchiveExtractor.parseLinkPairs("/s/a\0".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void symlinkCheckResolvesLikeTheKernel() {
        String root = "/srv/.kortty-extract.x";
        assertThat(SymlinkEscapeCheck.firstEscape(root, Map.of(root + "/a/link", "../b"))).isEmpty();
        assertThat(SymlinkEscapeCheck.firstEscape(root, Map.of(root + "/link", "/etc"))).hasValue("link");
        assertThat(SymlinkEscapeCheck.firstEscape(root, Map.of(root + "/a/link", "../../x"))).hasValue("a/link");
        assertThat(SymlinkEscapeCheck.firstEscape(root, Map.of("/elsewhere/link", "x"))).hasValue("/elsewhere/link");
        // the text "x/y/up/../../.." stays inside, but x/y/up is itself a link to ".."
        Map<String, String> chain = new java.util.LinkedHashMap<>();
        chain.put(root + "/x/y/up", "..");
        chain.put(root + "/l", "x/y/up/../../..");
        assertThat(SymlinkEscapeCheck.firstEscape(root, chain)).hasValue("l");
        // a loop reaches nothing
        Map<String, String> loop = new java.util.LinkedHashMap<>();
        loop.put(root + "/a", "b");
        loop.put(root + "/b", "a");
        assertThat(SymlinkEscapeCheck.firstEscape(root, loop)).isEmpty();
    }
}
