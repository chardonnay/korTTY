package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SnippetTextFileImportTest {

    @Test
    void importsPerlFileWithLanguageFromExtension() throws Exception {
        Path dir = Files.createTempDirectory("snippet-import");
        Path file = dir.resolve("data_test.pl");
        Files.writeString(file, "use strict;\r\nprint \"ok\\n\";\r\n", StandardCharsets.UTF_8);

        Snippet snippet = SnippetTextFileImport.importFile(file);

        assertThat(snippet.getName()).isEqualTo("data_test.pl");
        assertThat(snippet.getLanguage()).isEqualTo("perl");
        assertThat(snippet.getContent()).isEqualTo("use strict;\nprint \"ok\\n\";\n");
        assertThat(snippet.getLineCount()).isEqualTo(2);
    }

    @Test
    void unknownExtensionFallsBackToShebang() throws Exception {
        Path file = Files.createTempDirectory("snippet-import").resolve("deploy");
        Files.writeString(file, "#!/usr/bin/env bash\necho hi\n");

        assertThat(SnippetTextFileImport.importFile(file).getLanguage()).isEqualTo("bash");
    }

    @Test
    void latin1TextIsStillImported() throws Exception {
        Path file = Files.createTempDirectory("snippet-import").resolve("notes.txt");
        Files.write(file, "Grüße".getBytes(StandardCharsets.ISO_8859_1));

        Snippet snippet = SnippetTextFileImport.importFile(file);

        assertThat(snippet.getContent()).isEqualTo("Grüße");
        assertThat(snippet.getLanguage()).isEqualTo("plain");
    }

    @Test
    void binaryFileIsRejected() throws Exception {
        Path file = Files.createTempDirectory("snippet-import").resolve("image.png");
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D});

        assertThrows(SnippetTextFileImport.NotATextFileException.class, () -> SnippetTextFileImport.importFile(file));
    }

    @Test
    void recognizesSnippetExportsButNotPlainStructuredFiles() throws Exception {
        Path dir = Files.createTempDirectory("snippet-import");
        Path export = dir.resolve("kortty-snippets.json");
        Files.writeString(export, "{\"snippets\": [{\"name\": \"a\"}]}");
        Path config = dir.resolve("package.json");
        Files.writeString(config, "{\"name\": \"app\"}");
        Path yamlExport = dir.resolve("s.yaml");
        Files.writeString(yamlExport, "snippets:\n  - name: a\n");
        Path script = dir.resolve("run.sh");
        Files.writeString(script, "echo snippets");

        assertThat(SnippetTextFileImport.isSnippetExport(export)).isTrue();
        assertThat(SnippetTextFileImport.isSnippetExport(config)).isFalse();
        assertThat(SnippetTextFileImport.isSnippetExport(yamlExport)).isTrue();
        assertThat(SnippetTextFileImport.isSnippetExport(script)).isFalse();
    }
}
