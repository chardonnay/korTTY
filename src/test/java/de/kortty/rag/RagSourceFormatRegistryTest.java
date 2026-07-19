package de.kortty.rag;

import org.testng.annotations.Test;

import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

public class RagSourceFormatRegistryTest {
    private final RagSourceFormatRegistry registry = new RagSourceFormatRegistry();

    @Test
    void acceptsPlannedDocumentCodeAndConfigurationAllowlist() {
        for (String name : new String[] {
            "guide.pdf", "notes.txt", "README.md", "manual.adoc", "records.jsonl", "settings.cfg", "app.conf",
            "Program.cs", "index.php", "App.swift", "Main.scala", "init.lua", "config.fish",
            "setup.bat", "launch.cmd", "module.psm1", "main.py", "types.pyi", "app.tsx",
            "worker.mjs", "component.vue", "site.svelte", "theme.less", "build.gradle",
            "native.hxx", "query.sql", "build.kts",
            "DOCKERFILE", "Makefile"
        }) {
            assertThat(registry.isAllowed(Path.of(name))).isTrue();
        }
    }

    @Test
    void rejectsOfficeImagesArchivesExecutablesAndUnknownText() {
        for (String name : new String[] {
            "report.docx", "sheet.xlsx", "slides.pptx", "photo.png", "archive.zip",
            "program.exe", "library.jar", "debug.log", "unknown.bin"
        }) {
            assertThat(registry.isAllowed(Path.of(name))).isFalse();
        }
    }

    @Test
    void formatMatchingIsCaseInsensitiveAndUsesConfigurableCeiling() {
        assertThat(registry.formatFor(Path.of("GUIDE.PDF"))).hasValue(
            new RagSourceFormatRegistry.Format(
                "pdf", ".pdf", RagSourceFormatRegistry.MAX_CONFIGURABLE_FILE_BYTES, true));
        assertThat(registry.formatFor(Path.of("NOTES.TXT")).orElseThrow().maxBytes())
            .isEqualTo(RagSourceFormatRegistry.MAX_CONFIGURABLE_FILE_BYTES);
    }
}
