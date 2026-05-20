package de.kortty.core;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

public class RemoteTextFileSelectionSupportTest {

    @Test
    void normalizesSingleSelectedFileName() {
        assertThat(RemoteTextFileSelectionSupport.normalizeSelectedFileName("  notes.txt  "))
            .isEqualTo("notes.txt");
        assertThat(RemoteTextFileSelectionSupport.normalizeSelectedFileName("\"notes final.txt\""))
            .isEqualTo("notes final.txt");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsMultilineSelection() {
        RemoteTextFileSelectionSupport.normalizeSelectedFileName("one.txt\ntwo.txt");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsPathSeparatorsForSameDirectoryRule() {
        RemoteTextFileSelectionSupport.normalizeSelectedFileName("../secret.txt");
    }

    @Test
    void resolvesSelectionAgainstCurrentRemoteDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemoteFilePath("/home/daniel/work", "notes.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/work/notes.txt");
    }

    @Test
    void resolvesHomeRelativeWorkingDirectoryAgainstSftpStartDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemoteFilePath("~/work", "notes.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/work/notes.txt");
    }

    @Test
    void decodesUtf8TextFile() throws Exception {
        assertThat(RemoteTextFileSelectionSupport.decodeUtf8TextFile("hello\nwelt".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("hello\nwelt");
    }

    @Test(expectedExceptions = RemoteTextFileSelectionSupport.BinaryOrNonTextFileException.class)
    void rejectsNulByteBinaryFile() throws Exception {
        RemoteTextFileSelectionSupport.decodeUtf8TextFile(new byte[] {'a', 0, 'b'});
    }

    @Test(expectedExceptions = RemoteTextFileSelectionSupport.BinaryOrNonTextFileException.class)
    void rejectsInvalidUtf8File() throws Exception {
        RemoteTextFileSelectionSupport.decodeUtf8TextFile(new byte[] {(byte) 0xc3, 0x28});
    }
}
