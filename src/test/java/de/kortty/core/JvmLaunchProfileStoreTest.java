package de.kortty.core;

import de.kortty.model.JvmResourceProfile;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies the tiny launch-profile mirror that {@code de.kortty.JvmRelauncher} reads before any
 * app machinery starts: a round-trip persists, BALANCED deletes the file (so the relaunch path
 * short-circuits), and every failure mode degrades to BALANCED rather than throwing on the
 * critical startup path.
 */
public class JvmLaunchProfileStoreTest {

    @Test
    public void roundTripsANonDefaultProfile() throws IOException {
        Path dir = Files.createTempDirectory("kortty-jvm-store");
        JvmLaunchProfileStore.write(dir, JvmResourceProfile.MAXIMUM);
        assertThat(Files.isRegularFile(dir.resolve(JvmLaunchProfileStore.FILE_NAME))).isTrue();
        assertThat(JvmLaunchProfileStore.read(dir)).isEqualTo(JvmResourceProfile.MAXIMUM);
    }

    @Test
    public void missingFileReadsAsBalanced() throws IOException {
        Path dir = Files.createTempDirectory("kortty-jvm-store");
        assertThat(JvmLaunchProfileStore.read(dir)).isEqualTo(JvmResourceProfile.BALANCED);
    }

    @Test
    public void writingBalancedDeletesTheFile() throws IOException {
        Path dir = Files.createTempDirectory("kortty-jvm-store");
        JvmLaunchProfileStore.write(dir, JvmResourceProfile.HIGH);
        assertThat(Files.isRegularFile(dir.resolve(JvmLaunchProfileStore.FILE_NAME))).isTrue();

        JvmLaunchProfileStore.write(dir, JvmResourceProfile.BALANCED);
        assertThat(Files.exists(dir.resolve(JvmLaunchProfileStore.FILE_NAME))).isFalse();
        assertThat(JvmLaunchProfileStore.read(dir)).isEqualTo(JvmResourceProfile.BALANCED);
    }

    @Test
    public void corruptFileReadsAsBalanced() throws IOException {
        Path dir = Files.createTempDirectory("kortty-jvm-store");
        Files.writeString(dir.resolve(JvmLaunchProfileStore.FILE_NAME), "jvmResourceProfile=WAT\n");
        assertThat(JvmLaunchProfileStore.read(dir)).isEqualTo(JvmResourceProfile.BALANCED);
    }

    @Test
    public void nullConfigDirIsSafe() {
        assertThat(JvmLaunchProfileStore.read(null)).isEqualTo(JvmResourceProfile.BALANCED);
        JvmLaunchProfileStore.write(null, JvmResourceProfile.MAXIMUM); // must not throw
    }
}
