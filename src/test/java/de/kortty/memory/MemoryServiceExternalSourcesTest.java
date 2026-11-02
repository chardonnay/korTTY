package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.external.ExternalMemoryTool;
import de.kortty.model.AiTokenizerType;
import de.kortty.policy.UserMemoryMode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class MemoryServiceExternalSourcesTest {

    private Path root;
    private MemoryService service;

    @BeforeMethod
    void createService() throws IOException {
        root = Files.createTempDirectory("kortty-memory-external-");
        service = new MemoryService(new MemoryStore(root.resolve("memory")), name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        service.setExternalFilesAllowedSupplier(() -> true);
        service.setExternalSourcesSupplier(connectionId -> List.of(
            new ExternalSource("Claude Code: CLAUDE.md", "Use tabs in Perl.", ExternalMemoryTool.CLAUDE_CODE),
            new ExternalSource("Other: notes.md", "Keep notes short.", ExternalMemoryTool.OTHER)));
    }

    @AfterMethod(alwaysRun = true)
    void deleteTree() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void everyApprovedFileIsSentWithoutAnExclusion() {
        String text = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text();

        assertThat(text).contains("Use tabs in Perl.");
        assertThat(text).contains("Keep notes short.");
    }

    @Test
    void aCliThatReadsItsOwnFilesGetsOnlyTheOtherSources() {
        String text = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500, Set.of(ExternalMemoryTool.CLAUDE_CODE))
            .text();

        assertThat(text).doesNotContain("Use tabs in Perl.");
        assertThat(text).contains("Keep notes short.");
    }

    @Test
    void noExternalFileIsSentWhenThePolicyDeniesThem() {
        service.setExternalFilesAllowedSupplier(() -> false);

        String text = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text();

        assertThat(text).doesNotContain("Use tabs in Perl.");
        assertThat(text).doesNotContain("Keep notes short.");
    }
}
