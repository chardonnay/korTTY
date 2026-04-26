package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentFileTypeCountTest {

    @Test
    void detectsFileTypeCountRequestWithAbsoluteDirectory() {
        TerminalAgentService.FileTypeCountRequest request = TerminalAgentService.detectFileTypeCountRequest(
            "how many files are under directory /etc ? create a tabel and show how many files are binaries and how many are plain text.");

        assertNotNull(request);
        assertEquals("/etc", request.directory());
    }

    @Test
    void ignoresUnrelatedCountRequests() {
        assertNull(TerminalAgentService.detectFileTypeCountRequest("count files under directory /etc"));
    }

    @Test
    void buildsQuotedReadOnlyCommand() {
        String command = TerminalAgentService.buildFileTypeCountCommand("/tmp/has ' quote", true);

        assertTrue(command.startsWith("sudo -n sh -lc "));
        assertTrue(command.contains("'\"'\"'"));
        assertTrue(command.contains("file --mime-type -b"));
        assertTrue(command.contains("binary_or_non_text"));
    }

    @Test
    void parsesMachineReadableCountOutput() {
        TerminalAgentService.FileTypeCounts counts = TerminalAgentService.parseFileTypeCountOutput("""
            total=10
            plain_text=4
            binary_or_non_text=6
            """);

        assertNotNull(counts);
        assertEquals(10, counts.total());
        assertEquals(4, counts.plainText());
        assertEquals(6, counts.binaryOrNonText());
    }

    @Test
    void formatsCountTable() {
        String table = TerminalAgentService.formatFileTypeCountTable(
            "/etc",
            new TerminalAgentService.FileTypeCounts(10, 4, 6));

        assertTrue(table.contains("| Total files | 10 |"));
        assertTrue(table.contains("| Plain text files (`text/*`) | 4 |"));
        assertTrue(table.contains("| Binary/non-text files | 6 |"));
    }
}
