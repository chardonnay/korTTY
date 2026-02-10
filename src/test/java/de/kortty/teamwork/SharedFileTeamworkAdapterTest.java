package de.kortty.teamwork;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SharedFileTeamworkAdapter.toPath() to prevent regressions
 * on UNC, legacy file:////host/Share, and file:///C|/path handling.
 */
class SharedFileTeamworkAdapterTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void toPath_fileHostShare_preservesUnc() {
        Path p = SharedFileTeamworkAdapter.toPath("file://host/Share");
        assertNotNull(p);
        assertTrue(p.toString().replace('\\', '/').startsWith("//"), "UNC should start with //");
        assertTrue(p.toString().contains("host"), "UNC should contain host");
        assertTrue(p.toString().contains("Share"), "UNC should contain Share");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void toPath_fileFourSlashHostShare_preservesUnc() {
        Path p = SharedFileTeamworkAdapter.toPath("file:////host/Share");
        assertNotNull(p);
        String s = p.toString().replace('\\', '/');
        assertTrue(s.startsWith("//"), "Legacy file://// should yield path starting with //");
        assertTrue(s.contains("host"), "Path should contain host");
        assertTrue(s.contains("Share"), "Path should contain Share");
    }

    @Test
    void toPath_filePipeDrive_normalizesToColon() {
        Path p = SharedFileTeamworkAdapter.toPath("file:///C|/path/to/file");
        assertNotNull(p);
        String s = p.toString().replace('\\', '/');
        assertTrue(s.contains("C:"), "C| should be normalized to C:");
        assertFalse(s.contains("|"), "Pipe should not appear in path");
        assertTrue(s.contains("path"), "Path segment should be preserved");
    }

    @Test
    void toPath_nullOrBlank_returnsNull() {
        assertNull(SharedFileTeamworkAdapter.toPath(null));
        assertNull(SharedFileTeamworkAdapter.toPath(""));
        assertNull(SharedFileTeamworkAdapter.toPath("   "));
    }

    @Test
    void toPath_plainPath_unchanged() {
        Path p = SharedFileTeamworkAdapter.toPath("/home/user/file");
        assertNotNull(p);
        assertEquals(Paths.get("/home/user/file"), p);
    }

    @Test
    void teamworkRecycleBinServiceJaxbContextLoads() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recycle-test");
        try {
            TeamworkRecycleBinService service = new TeamworkRecycleBinService(dir);
            service.load();
            assertTrue(service.getDeleted().isEmpty());
        } finally {
            Files.deleteIfExists(dir);
        }
    }
}
