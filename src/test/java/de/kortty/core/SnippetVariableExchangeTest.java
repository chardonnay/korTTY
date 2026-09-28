package de.kortty.core;

import de.kortty.model.SnippetVariable;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SnippetVariableExchangeTest {

    private static final List<SnippetVariable> VARIABLES = List.of(
            new SnippetVariable("deploy_host", "web-01.example.test"),
            new SnippetVariable("motd", "line one\nline \"two\": ok # not a comment\\"),
            new SnippetVariable("empty", ""));

    @Test
    void roundTripsEveryFormat() throws Exception {
        for (SnippetVariableExchange.Format format : SnippetVariableExchange.Format.values()) {
            Path file = Files.createTempDirectory("vars").resolve("vars." + format.extension());
            SnippetVariableExchange.export(file, VARIABLES, format);

            List<SnippetVariable> imported = SnippetVariableExchange.importFile(file);

            assertThat(imported).hasSize(3);
            for (int i = 0; i < VARIABLES.size(); i++) {
                assertThat(imported.get(i).getName()).isEqualTo(VARIABLES.get(i).getName());
                assertThat(imported.get(i).getValue()).isEqualTo(VARIABLES.get(i).getValue());
            }
        }
    }

    @Test
    void readsHandWrittenYamlAndSkipsNamelessEntries() throws Exception {
        String yaml = """
                # exported by hand
                variables:
                  - name: user
                    value: 'it''s me'
                  - name: port
                    value: 8080 # default
                  - value: orphan
                """;

        List<SnippetVariable> imported = SnippetVariableExchange.read(yaml, SnippetVariableExchange.Format.YAML);

        assertThat(imported).hasSize(2);
        assertThat(imported.get(0).getValue()).isEqualTo("it's me");
        assertThat(imported.get(1).getValue()).isEqualTo("8080");
    }

    @Test
    void readsBareJsonArrayAndStoredXmlShape() throws Exception {
        assertThat(SnippetVariableExchange.read("[{\"name\":\"a\",\"value\":\"1\"}]",
                SnippetVariableExchange.Format.JSON)).hasSize(1);

        String storedXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <snippetVariables>
                    <variable><name>host</name><value>db</value></variable>
                </snippetVariables>
                """;
        List<SnippetVariable> imported = SnippetVariableExchange.read(storedXml, SnippetVariableExchange.Format.XML);
        assertThat(imported.getFirst().getName()).isEqualTo("host");
        assertThat(imported.getFirst().getValue()).isEqualTo("db");
    }

    @Test
    void rejectsJsonWithoutVariables() {
        assertThrows(Exception.class, () -> SnippetVariableExchange.read("{\"other\": 1}",
                SnippetVariableExchange.Format.JSON));
    }

    @Test
    void rejectsUnsupportedExtension() throws Exception {
        Path file = Files.createTempDirectory("vars").resolve("vars.txt");
        Files.writeString(file, "x");
        assertThrows(Exception.class, () -> SnippetVariableExchange.importFile(file));
    }
}
