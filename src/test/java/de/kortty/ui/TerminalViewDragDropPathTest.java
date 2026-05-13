package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class TerminalViewDragDropPathTest {

    @Test
    void appendRemotePathHandlesAbsoluteBase() {
        assertThat(TerminalView.appendRemotePath("/home/daniel", "file.txt"))
                .isEqualTo("/home/daniel/file.txt");
        assertThat(TerminalView.appendRemotePath("/", "file.txt"))
                .isEqualTo("/file.txt");
    }

    @Test
    void appendRemotePathHandlesRelativeBase() {
        assertThat(TerminalView.appendRemotePath(".", "file.txt"))
                .isEqualTo("./file.txt");
        assertThat(TerminalView.appendRemotePath("work", "file.txt"))
                .isEqualTo("work/file.txt");
    }

    @Test
    void parentRemotePathHandlesRootAndRelativePaths() {
        assertThat(TerminalView.parentRemotePath("/home/daniel/file.txt"))
                .isEqualTo("/home/daniel");
        assertThat(TerminalView.parentRemotePath("/file.txt"))
                .isEqualTo("/");
        assertThat(TerminalView.parentRemotePath("file.txt"))
                .isEqualTo(".");
        assertThat(TerminalView.parentRemotePath("dir/file.txt"))
                .isEqualTo("dir");
    }

    @Test
    void resolvesHomeRelativeTrackedDirectoryAgainstSftpStartDirectory() {
        assertThat(TerminalView.resolveDragDropRemoteDirectory("~/Dokumente", "/home/daniel"))
                .isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void resolvesUnknownTrackedDirectoryToSftpStartDirectory() {
        assertThat(TerminalView.resolveDragDropRemoteDirectory(null, "/home/daniel"))
                .isEqualTo("/home/daniel");
        assertThat(TerminalView.resolveDragDropRemoteDirectory("~", "/home/daniel"))
                .isEqualTo("/home/daniel");
    }

    @Test
    void keepsAbsoluteTrackedDirectory() {
        assertThat(TerminalView.resolveDragDropRemoteDirectory("/var/tmp", "/home/daniel"))
                .isEqualTo("/var/tmp");
    }
}
