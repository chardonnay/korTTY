package de.kortty.core;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;

import static com.google.common.truth.Truth.assertThat;

class LocalShellDirectoryChangeTrackerTest {

    @DataProvider(name = "directoryChangeCommands")
    Object[][] directoryChangeCommands() {
        return new Object[][] {
            {"cd /tmp"},
            {"chdir /tmp"},
            {"pushd /tmp"},
            {"popd"},
            {"Push-Location C:\\work"},
            {"Pop-Location"},
            {"Set-Location C:\\work"},
            {"SL C:\\work"},
            {"D:"},
            {"echo ok && cd /tmp"},
            {"cd.."},
            {"cd\\work"},
            {"builtin cd /tmp"}
        };
    }

    @Test(dataProvider = "directoryChangeCommands")
    void recognizesPotentialDirectoryChangeCommands(String command) {
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory(command)).isTrue();
    }

    @Test
    void ignoresDirectoryWordsThatAreNotCommands() {
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("echo cd /tmp")).isFalse();
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("echo 'cd /tmp'")).isFalse();
        assertThat(LocalShellDirectoryChangeTracker.mayChangeWorkingDirectory("printf set-location")).isFalse();
    }

    @Test
    void tracksCommandsAcrossWriteChunksAndEdits() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("c".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {'x', '\b', 'd', ' ', '/'})).isFalse();
        assertThat(tracker.accept("tmp\r".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void ctrlUClearsAnAbandonedDirectoryChange() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("cd /wrong".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {0x15})).isFalse();
        assertThat(tracker.accept("echo safe\r".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void bracketedPasteIsInspectedOnlyWhenSubmitted() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("\u001b[20".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept("0~echo first\ncd '/tmp/space dir'\u001b[201~"
            .getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(tracker.accept(new byte[] {'\r'})).isTrue();
    }

    @Test
    void splitUtf8PathDoesNotLoseAsciiCommandPrefix() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        byte[] command = "cd /tmp/Grüße\r".getBytes(StandardCharsets.UTF_8);
        byte[] first = java.util.Arrays.copyOfRange(command, 0, 11);
        byte[] second = java.util.Arrays.copyOfRange(command, 11, command.length);
        assertThat(tracker.accept(first)).isFalse();
        assertThat(tracker.accept(second)).isTrue();
    }

    @Test
    void crlfSubmitsOnlyOneLogicalLine() {
        LocalShellDirectoryChangeTracker tracker = new LocalShellDirectoryChangeTracker();
        assertThat(tracker.accept("cd /tmp\r\n".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(tracker.accept("echo safe\r\n".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
