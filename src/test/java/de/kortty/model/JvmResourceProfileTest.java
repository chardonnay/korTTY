package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the JVM-options resolution that {@code de.kortty.JvmRelauncher} feeds into
 * {@code _JAVA_OPTIONS}. A wrong string here would either fail to change the heap or, worse, brick
 * the packaged app at JVM startup, so the exact shape of each profile's options is verified.
 */
public class JvmResourceProfileTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    public void balancedNeverRelaunches() {
        assertThat(JvmResourceProfile.BALANCED.resolveJavaOptions(32 * GB)).isNull();
    }

    @Test
    public void highRaisesHeapToHalfOfRamOnG1() {
        // 16 GB machine -> 50% = 8192 MB, no collector override (G1 stays the JDK default).
        String opts = JvmResourceProfile.HIGH.resolveJavaOptions(16 * GB);
        assertThat(opts).isEqualTo("-Xmx8192m");
        assertThat(opts).doesNotContain("UseZGC");
    }

    @Test
    public void maximumUsesZgcAndThreeQuartersOfRam() {
        // 32 GB machine -> 75% = 24576 MB, with ZGC.
        String opts = JvmResourceProfile.MAXIMUM.resolveJavaOptions(32 * GB);
        assertThat(opts).contains("-XX:+UseZGC");
        assertThat(opts).contains("-Xmx24576m");
    }

    @Test
    public void neverGoesBelowTheShipped2gDefault() {
        // 2 GB machine: 50% would be 1 GB, but the floor keeps it at the 2048 MB default.
        assertThat(JvmResourceProfile.HIGH.resolveJavaOptions(2 * GB)).isEqualTo("-Xmx2048m");
        assertThat(JvmResourceProfile.MAXIMUM.resolveJavaOptions(2 * GB)).contains("-Xmx2048m");
    }

    @Test
    public void usesFixedFallbacksWhenRamIsUnknown() {
        // ram <= 0: fixed fallbacks, never below the 2 GB floor.
        assertThat(JvmResourceProfile.HIGH.resolveJavaOptions(0)).isEqualTo("-Xmx4096m");
        assertThat(JvmResourceProfile.MAXIMUM.resolveJavaOptions(-1)).isEqualTo("-XX:+UseZGC -Xmx6144m");
    }

    @Test
    public void fromNameIsLenientAndDefaultsToBalanced() {
        assertThat(JvmResourceProfile.fromName("MAXIMUM")).isEqualTo(JvmResourceProfile.MAXIMUM);
        assertThat(JvmResourceProfile.fromName(" high ")).isEqualTo(JvmResourceProfile.HIGH);
        assertThat(JvmResourceProfile.fromName("nonsense")).isEqualTo(JvmResourceProfile.BALANCED);
        assertThat(JvmResourceProfile.fromName(null)).isEqualTo(JvmResourceProfile.BALANCED);
    }
}
