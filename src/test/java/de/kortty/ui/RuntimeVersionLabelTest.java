package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The Version column of the runtimes table shows the bare version out of a package id.
 *
 * <p>The fixtures are the real naming schemes: llama package ids are validated against
 * {@code llama-b[0-9]+-kortty[1-9][0-9]*} in LlamaRuntimePackageDescriptor, and the MLX runtime
 * installed on a development machine is {@code mlx-0.31.3-kortty2-macos-aarch64}.
 */
class RuntimeVersionLabelTest {

    @Test
    void extractsTheLlamaBuildNumber() {
        assertThat(LocalModelManagerPane.runtimeVersionLabel("llama-b10103-kortty2"))
            .isEqualTo("b10103");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("llama-b9-kortty1"))
            .isEqualTo("b9");
    }

    @Test
    void extractsTheMlxVersionWithoutPlatformOrArchitecture() {
        assertThat(LocalModelManagerPane.runtimeVersionLabel("mlx-0.31.3-kortty2-macos-aarch64"))
            .isEqualTo("0.31.3");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("mlx-1.0.0-kortty10-macos-x86_64"))
            .isEqualTo("1.0.0");
    }

    /**
     * An unrecognised id is shown as-is. If the packaging scheme ever changes, the raw id is
     * honest; a guessed fragment would quietly display the wrong version.
     */
    @Test
    void anUnknownFormatIsShownUnchanged() {
        assertThat(LocalModelManagerPane.runtimeVersionLabel("something-else-entirely"))
            .isEqualTo("something-else-entirely");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("llama-b10103"))
            .isEqualTo("llama-b10103");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("0.31.3")).isEqualTo("0.31.3");
    }

    @Test
    void aMissingIdRendersAsADash() {
        assertThat(LocalModelManagerPane.runtimeVersionLabel(null)).isEqualTo("—");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("")).isEqualTo("—");
        assertThat(LocalModelManagerPane.runtimeVersionLabel("   ")).isEqualTo("—");
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        assertThat(LocalModelManagerPane.runtimeVersionLabel("  mlx-0.31.3-kortty2-macos-aarch64  "))
            .isEqualTo("0.31.3");
    }
}
