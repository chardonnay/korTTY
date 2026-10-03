package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SftpDragPayloadTest {

    private static final String TAB = "3f2a9c1e-tab";

    @Test
    void roundTripKeepsPathsAndKinds() {
        List<SftpDragPayload.Entry> entries = List.of(
            new SftpDragPayload.Entry("/srv/app/logs", true),
            new SftpDragPayload.Entry("/srv/app/report.txt", false));

        Optional<List<SftpDragPayload.Entry>> decoded =
            SftpDragPayload.decode(SftpDragPayload.encode(TAB, entries), TAB);

        assertThat(decoded).hasValue(entries);
        assertThat(decoded.get().get(0).name()).isEqualTo("logs");
        assertThat(decoded.get().get(1).name()).isEqualTo("report.txt");
    }

    @Test
    void anotherTabsDragIsNotOurs() {
        String payload = SftpDragPayload.encode(TAB, List.of(new SftpDragPayload.Entry("/etc/hosts", false)));

        // Another tab may be connected to another server: it must not read these paths.
        assertThat(SftpDragPayload.decode(payload, "other-tab")).isEmpty();
        assertThat(SftpDragPayload.decode(payload, null)).isEmpty();
        assertThat(SftpDragPayload.decode(null, TAB)).isEmpty();
    }

    @Test
    void pathsWithSpacesTabsLineBreaksAndBackslashesSurvive() {
        List<SftpDragPayload.Entry> entries = List.of(
            new SftpDragPayload.Entry("/home/demo/my file.txt", false),
            new SftpDragPayload.Entry("/home/demo/tab\there", false),
            new SftpDragPayload.Entry("/home/demo/line\nbreak\r", true),
            new SftpDragPayload.Entry("/home/demo/back\\slash\\n", false));

        String payload = SftpDragPayload.encode(TAB, entries);

        assertThat(payload.split("\n")).hasLength(entries.size() + 1);
        assertThat(SftpDragPayload.decode(payload, TAB)).hasValue(entries);
        assertThat(SftpDragPayload.decode(payload, TAB).get().get(1).name()).isEqualTo("tab\there");
    }

    @Test
    void textThatIsNoPayloadIsRejected() {
        assertThat(SftpDragPayload.decode(TAB, TAB)).isEmpty();
        assertThat(SftpDragPayload.decode(TAB + "\nX\t/etc/hosts", TAB)).isEmpty();
        assertThat(SftpDragPayload.decode(TAB + "\nF/etc/hosts", TAB)).isEmpty();
        assertThat(SftpDragPayload.decode(TAB + "\nF\t", TAB)).isEmpty();
        assertThat(SftpDragPayload.decode(TAB + "\nF\t/broken\\x", TAB)).isEmpty();
        assertThat(SftpDragPayload.decode(TAB + "\nF\t/dangling\\", TAB)).isEmpty();
    }

    @Test
    void tabIdMustBeOneLine() {
        expectThrows(IllegalArgumentException.class, () -> SftpDragPayload.encode("a\nb", List.of()));
        expectThrows(IllegalArgumentException.class, () -> SftpDragPayload.encode(" ", List.of()));
    }

    @Test
    void nameIsTheLastSegment() {
        assertThat(new SftpDragPayload.Entry("/srv/app/", true).name()).isEqualTo("app");
        assertThat(new SftpDragPayload.Entry("relative", false).name()).isEqualTo("relative");
    }
}
