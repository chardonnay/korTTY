package de.kortty;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

public class JavaFxPlatformSupportTest {

    @Test
    public void selectsSoftwareRendererForEmulatedX64OnWindowsArm() {
        assertThat(JavaFxPlatformSupport.requiresSoftwareRenderer(
                "Windows 11", "amd64", "ARMv8 (64-bit) Family 8", null)).isTrue();
        assertThat(JavaFxPlatformSupport.requiresSoftwareRenderer(
                "Windows 11", "x86_64", "Intel64 Family 6", "ARM64")).isTrue();
    }

    @Test
    public void keepsHardwareRendererOnNativeX64AndOtherPlatforms() {
        assertThat(JavaFxPlatformSupport.requiresSoftwareRenderer(
                "Windows 11", "amd64", "Intel64 Family 6", null)).isFalse();
        assertThat(JavaFxPlatformSupport.requiresSoftwareRenderer(
                "Linux", "amd64", "ARMv8 (64-bit)", null)).isFalse();
        assertThat(JavaFxPlatformSupport.requiresSoftwareRenderer(
                "Windows 11", "aarch64", "ARMv8 (64-bit)", null)).isFalse();
    }
}
