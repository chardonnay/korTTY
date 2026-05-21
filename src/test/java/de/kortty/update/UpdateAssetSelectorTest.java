package de.kortty.update;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class UpdateAssetSelectorTest {

    private final UpdateAssetSelector selector = new UpdateAssetSelector();

    @Test
    void selectsWindowsMsiForMatchingArchitecture() {
        UpdateAsset selected = selector.select(
                release(
                    "korTTY-Windows-2.3.0-x86_64.zip",
                    "korTTY-Windows-2.3.0-x86_64.msi",
                    "korTTY-Java-2.3.0.zip"),
                new PlatformProfile(OperatingSystem.WINDOWS, "amd64", null, Set.of()))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("korTTY-Windows-2.3.0-x86_64.msi");
    }

    @Test
    void selectsMacDmgForArmArchitecture() {
        UpdateAsset selected = selector.select(
                release(
                    "korTTY-macOS-2.3.0-aarch64.zip",
                    "korTTY-macOS-2.3.0-aarch64.dmg"),
                new PlatformProfile(OperatingSystem.MACOS, "arm64", null, Set.of()))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("korTTY-macOS-2.3.0-aarch64.dmg");
    }

    @Test
    void selectsDebForDebianLikeLinux() {
        UpdateAsset selected = selector.select(
                release(
                    "kortty-Linux-2.3.0-x86_64.tar.gz",
                    "kortty-Linux-2.3.0-x86_64.deb"),
                new PlatformProfile(OperatingSystem.LINUX, "x86_64", "ubuntu", Set.of("debian")))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("kortty-Linux-2.3.0-x86_64.deb");
    }

    @Test
    void selectsRpmForFedoraLikeLinux() {
        UpdateAsset selected = selector.select(
                release(
                    "kortty-Linux-2.3.0-x86_64.tar.gz",
                    "kortty-Linux-2.3.0-x86_64.rpm"),
                new PlatformProfile(OperatingSystem.LINUX, "x86_64", "fedora", Set.of()))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("kortty-Linux-2.3.0-x86_64.rpm");
    }

    @Test
    void selectsArchPackageForArchLinux() {
        UpdateAsset selected = selector.select(
                release(
                    "kortty-Linux-2.3.0-x86_64.tar.gz",
                    "kortty-2.3.0-1-x86_64.pkg.tar.zst"),
                new PlatformProfile(OperatingSystem.LINUX, "x86_64", "arch", Set.of()))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("kortty-2.3.0-1-x86_64.pkg.tar.zst");
    }

    @Test
    void fallsBackToJavaZipWhenNoNativeAssetMatches() {
        UpdateAsset selected = selector.select(
                release("korTTY-Java-2.3.0.zip"),
                new PlatformProfile(OperatingSystem.MACOS, "x86_64", null, Set.of()))
            .orElseThrow();

        assertThat(selected.name()).isEqualTo("korTTY-Java-2.3.0.zip");
    }

    private static UpdateRelease release(String... assetNames) {
        return new UpdateRelease(
            "v2.3.0",
            "korTTY v2.3.0",
            URI.create("https://example.test/releases/v2.3.0"),
            Instant.parse("2026-05-20T10:00:00Z"),
            false,
            false,
            List.of(assetNames).stream().map(UpdateAssetSelectorTest::asset).toList());
    }

    private static UpdateAsset asset(String name) {
        return new UpdateAsset(
            name,
            URI.create("https://example.test/downloads/" + name),
            12,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000");
    }
}
