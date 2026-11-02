package de.kortty.ui;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.runtimeupdate.LlamaRuntimePlatform;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** The Backend cells of the Local Models tab name the real backend, before and after a runtime is installed. */
class LocalModelManagerPaneBackendTest {

    @Test
    void anInstalledRuntimeShowsItsPackagesBackend() {
        assertThat(LocalModelManagerPane.runtimeBackendCell(LlamaBackend.VULKAN, LlamaBackend.CPU))
            .isEqualTo(I18n.get("ai.local.runtime.backend.vulkan"));
    }

    @Test
    void aMissingRuntimeShowsTheBackendAnInstallWouldBring() {
        assertThat(LocalModelManagerPane.runtimeBackendCell(null, LlamaBackend.METAL))
            .isEqualTo(I18n.get("ai.local.models.runtime.backend.planned", I18n.get("ai.local.runtime.backend.metal")));
        assertThat(LocalModelManagerPane.runtimeBackendCell(null, null)).isEqualTo("—");
    }

    @Test
    void autoPlansTheBackendTheInstallerPicksOnEachPlatform() {
        assertThat(LocalModelManagerPane.plannedLlamaBackend(LlamaBackend.AUTO, LlamaRuntimePlatform.MACOS))
            .isEqualTo(LlamaBackend.METAL);
        assertThat(LocalModelManagerPane.plannedLlamaBackend(LlamaBackend.AUTO, LlamaRuntimePlatform.WINDOWS))
            .isEqualTo(LlamaBackend.CPU);
        assertThat(LocalModelManagerPane.plannedLlamaBackend(LlamaBackend.AUTO, LlamaRuntimePlatform.LINUX))
            .isEqualTo(LlamaBackend.CPU);
        assertThat(LocalModelManagerPane.plannedLlamaBackend(null, LlamaRuntimePlatform.MACOS))
            .isEqualTo(LlamaBackend.METAL);
        assertThat(LocalModelManagerPane.plannedLlamaBackend(LlamaBackend.AUTO, null)).isNull();
    }

    @Test
    void anExplicitPreferenceIsWhatAnInstallBrings() {
        assertThat(LocalModelManagerPane.plannedLlamaBackend(LlamaBackend.VULKAN, LlamaRuntimePlatform.LINUX))
            .isEqualTo(LlamaBackend.VULKAN);
    }

    @Test
    void backendsReadAsTheirNamesNotTheEnumConstants() {
        assertThat(LocalModelManagerPane.backendLabel(LlamaBackend.METAL)).isEqualTo(I18n.get("ai.local.runtime.backend.metal"));
        assertThat(LocalModelManagerPane.backendLabel(LlamaBackend.AUTO)).isEqualTo(I18n.get("ai.local.models.backend.auto"));
        assertThat(LocalModelManagerPane.backendLabel(LlamaBackend.METAL)).isNotEqualTo("METAL");
    }
}
