package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


class TerminalAgentFileTypeCountTest {

    @Test
    void detectsFileTypeCountRequestWithAbsoluteDirectory() {
        TerminalAgentService.FileTypeCountRequest request = TerminalAgentService.detectFileTypeCountRequest(
            "how many files are under directory /etc ? create a tabel and show how many files are binaries and how many are plain text.");

        assertThat(request).isNotNull();
        assertThat(request.directory()).isEqualTo("/etc");
    }

    @Test
    void ignoresUnrelatedCountRequests() {
        assertThat(TerminalAgentService.detectFileTypeCountRequest("count files under directory /etc")).isNull();
    }

    @Test
    void buildsQuotedReadOnlyCommand() {
        String command = TerminalAgentService.buildFileTypeCountCommand("/tmp/has ' quote", true);

        assertThat(command.startsWith("sudo -n sh -lc ")).isTrue();
        assertThat(command.contains("'\"'\"'")).isTrue();
        assertThat(command.contains("file --mime-type -b")).isTrue();
        assertThat(command.contains("binary_or_non_text")).isTrue();
    }

    @Test
    void parsesMachineReadableCountOutput() {
        TerminalAgentService.FileTypeCounts counts = TerminalAgentService.parseFileTypeCountOutput("""
            total=10
            plain_text=4
            binary_or_non_text=6
            """);

        assertThat(counts).isNotNull();
        assertThat(counts.total()).isEqualTo(10);
        assertThat(counts.plainText()).isEqualTo(4);
        assertThat(counts.binaryOrNonText()).isEqualTo(6);
    }

    @Test
    void formatsCountTable() {
        String table = TerminalAgentService.formatFileTypeCountTable(
            "/etc",
            new TerminalAgentService.FileTypeCounts(10, 4, 6));

        assertThat(table.contains("| Total files | 10 |")).isTrue();
        assertThat(table.contains("| Plain text files (`text/*`) | 4 |")).isTrue();
        assertThat(table.contains("| Binary/non-text files | 6 |")).isTrue();
    }
}
