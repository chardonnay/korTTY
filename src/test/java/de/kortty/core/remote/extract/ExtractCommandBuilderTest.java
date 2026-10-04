package de.kortty.core.remote.extract;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class ExtractCommandBuilderTest {

    private static final String ARCHIVE = "/srv/up loads/it's $(x).tar.gz";
    private static final String QUOTED_ARCHIVE = "'/srv/up loads/it'\\''s $(x).tar.gz'";
    private static final String STAGING = "/srv/up loads/.kortty-extract.Ab12Cd";

    @Test
    public void detectsTheSupportedFormats() {
        assertThat(ArchiveKind.detect("a.zip")).hasValue(ArchiveKind.ZIP);
        assertThat(ArchiveKind.detect("A.ZIP")).hasValue(ArchiveKind.ZIP);
        assertThat(ArchiveKind.detect("a.tar")).hasValue(ArchiveKind.TAR);
        assertThat(ArchiveKind.detect("a.tar.gz")).hasValue(ArchiveKind.TAR_GZ);
        assertThat(ArchiveKind.detect("a.tgz")).hasValue(ArchiveKind.TAR_GZ);
        assertThat(ArchiveKind.detect("a.tar.bz2")).hasValue(ArchiveKind.TAR_BZ2);
        assertThat(ArchiveKind.detect("a.tar.xz")).hasValue(ArchiveKind.TAR_XZ);
        assertThat(ArchiveKind.detect("a.7z")).hasValue(ArchiveKind.SEVEN_ZIP);
        assertThat(ArchiveKind.detect("a.gz")).isEmpty();
        assertThat(ArchiveKind.detect("a.rar")).isEmpty();
        assertThat(ArchiveKind.detect(".zip")).isEmpty();
    }

    @Test
    public void folderNameDropsTheWholeExtension() {
        assertThat(ArchiveKind.folderName("site-1.2.tar.gz")).isEqualTo("site-1.2");
        assertThat(ArchiveKind.folderName("Photos.ZIP")).isEqualTo("Photos");
        assertThat(ArchiveKind.folderName("x.tgz")).isEqualTo("x");
        assertThat(ArchiveKind.folderName("...zip")).isEqualTo("extracted");
    }

    @Test
    public void tarExtractsIntoTheStagingFolderWithoutRestoringOwners() {
        String command = ExtractCommandBuilder.extract(ArchiveKind.TAR_GZ, ARCHIVE, STAGING);
        assertThat(command).isEqualTo("cd '" + STAGING + "' && LC_ALL=C tar -x --no-same-owner -z -f "
            + QUOTED_ARCHIVE + " </dev/null");
        assertThat(ExtractCommandBuilder.extract(ArchiveKind.TAR, ARCHIVE, STAGING))
            .contains("tar -x --no-same-owner -f ");
        assertThat(ExtractCommandBuilder.extract(ArchiveKind.TAR_BZ2, ARCHIVE, STAGING)).contains(" -j -f ");
        assertThat(ExtractCommandBuilder.extract(ArchiveKind.TAR_XZ, ARCHIVE, STAGING)).contains(" -J -f ");
    }

    @Test
    public void unzipNeverOverwritesAndNeverAllowsParentPaths() {
        String command = ExtractCommandBuilder.extract(ArchiveKind.ZIP, "/a/b.zip", STAGING);
        assertThat(command).isEqualTo("LC_ALL=C unzip -qq -n '/a/b.zip' -d '" + STAGING + "' </dev/null");
        assertThat(command).doesNotContain("-:");
    }

    @Test
    public void sevenZipGetsTheOutputFolderAsOneQuotedWord() {
        String command = ExtractCommandBuilder.extract(ArchiveKind.SEVEN_ZIP, "/a/b.7z", STAGING);
        assertThat(command).contains("x -y -bd -aos '-o" + STAGING + "' '/a/b.7z' </dev/null");
        assertThat(command).doesNotContain(" -p");
    }

    @Test
    public void listingCommandsPerFormat() {
        assertThat(ExtractCommandBuilder.listCommands(ArchiveKind.ZIP, "/a/b.zip", false)).containsExactly(
            "LC_ALL=C unzip -Z1 '/a/b.zip' </dev/null", "LC_ALL=C unzip -Z '/a/b.zip' </dev/null").inOrder();
        assertThat(ExtractCommandBuilder.listCommands(ArchiveKind.TAR_GZ, "/a/b.tgz", true)).containsExactly(
            "LC_ALL=C tar --quoting-style=escape -tv -z -f '/a/b.tgz' </dev/null");
        assertThat(ExtractCommandBuilder.listCommands(ArchiveKind.TAR_GZ, "/a/b.tgz", false)).containsExactly(
            "LC_ALL=C tar -t -z -f '/a/b.tgz' </dev/null", "LC_ALL=C tar -tv -z -f '/a/b.tgz' </dev/null").inOrder();
        assertThat(ExtractCommandBuilder.listCommands(ArchiveKind.SEVEN_ZIP, "/a/b.7z", false).get(0))
            .endsWith(" l -slt -ba '/a/b.7z' </dev/null");
    }

    @Test
    public void stagingFolderIsAPrivateSiblingOfTheArchive() {
        assertThat(ExtractCommandBuilder.makeStaging("/srv/up loads"))
            .isEqualTo("mktemp -d '/srv/up loads/.kortty-extract.XXXXXX'");
        assertThat(ExtractCommandBuilder.makeStaging("/")).isEqualTo("mktemp -d '/.kortty-extract.XXXXXX'");
        assertThat(ExtractCommandBuilder.isStagingPath("/srv/up loads", STAGING)).isTrue();
        assertThat(ExtractCommandBuilder.isStagingPath("/srv/up loads", "/srv/up loads/.kortty-extract.")).isFalse();
        assertThat(ExtractCommandBuilder.isStagingPath("/srv/up loads", "/srv/up loads/other")).isFalse();
        assertThat(ExtractCommandBuilder.isStagingPath("/srv/up loads", "/srv/up loads/.kortty-extract.a/../..")).isFalse();
        assertThat(ExtractCommandBuilder.isStagingPath("/srv", "/srv/.kortty-extract.ab\n")).isFalse();
        assertThat(ExtractCommandBuilder.isStagingPath("/srv", null)).isFalse();
    }

    @Test
    public void linkScanNeverFollowsLinks() {
        String command = ExtractCommandBuilder.linkScan(STAGING);
        assertThat(command).startsWith("find -P '" + STAGING + "' -type l -exec sh -c '");
        assertThat(command).contains("readlink --");
        assertThat(command).endsWith(" sh {} +");
    }

    @Test
    public void finishMovesToTheFirstFreeNameOnly() {
        String command = ExtractCommandBuilder.finish(STAGING, List.of("/srv/a b", "/srv/a b (1)"));
        assertThat(command).isEqualTo("chmod \"$(umask -S)\" '" + STAGING + "' && for t in '/srv/a b' '/srv/a b (1)';"
            + " do if [ ! -e \"$t\" ] && [ ! -L \"$t\" ]; then mv -- '" + STAGING
            + "' \"$t\" && printf '%s' \"$t\"; exit; fi; done; exit 73");
        assertThrows(IllegalArgumentException.class, () -> ExtractCommandBuilder.finish(STAGING, List.of()));
    }

    @Test
    public void cleanupRemovesOnlyTheStagingFolder() {
        assertThat(ExtractCommandBuilder.cleanup(STAGING))
            .isEqualTo("chmod -R u+rwx '" + STAGING + "' 2>/dev/null; rm -rf -- '" + STAGING + "'");
    }

    @Test
    public void relativePathsAreRefused() {
        assertThrows(IllegalArgumentException.class,
            () -> ExtractCommandBuilder.extract(ArchiveKind.ZIP, "-rf.zip", STAGING));
        assertThrows(IllegalArgumentException.class,
            () -> ExtractCommandBuilder.extract(ArchiveKind.ZIP, "/a.zip", "relative"));
        assertThrows(IllegalArgumentException.class, () -> ExtractCommandBuilder.makeStaging("tmp"));
        assertThrows(IllegalArgumentException.class, () -> ExtractCommandBuilder.linkScan("-delete"));
    }

    @Test
    public void probesNameTheTool() {
        assertThat(ExtractCommandBuilder.toolProbe(ArchiveKind.ZIP)).isEqualTo("command -v unzip >/dev/null 2>&1");
        assertThat(ExtractCommandBuilder.toolProbe(ArchiveKind.TAR_XZ)).isEqualTo("command -v tar >/dev/null 2>&1");
        assertThat(ExtractCommandBuilder.toolProbe(ArchiveKind.SEVEN_ZIP)).contains("command -v 7za");
    }

    @Test
    public void targetCandidatesCountUp() {
        List<String> candidates = RemoteArchiveExtractor.targetCandidates("/srv", "site");
        assertThat(candidates).hasSize(RemoteArchiveExtractor.MAX_NAME_CANDIDATES);
        assertThat(candidates.subList(0, 3)).containsExactly("/srv/site", "/srv/site (1)", "/srv/site (2)").inOrder();
        assertThat(RemoteArchiveExtractor.targetCandidates("/", "x").get(0)).isEqualTo("/x");
    }
}
