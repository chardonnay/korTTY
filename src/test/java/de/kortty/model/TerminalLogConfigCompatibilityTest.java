package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

class TerminalLogConfigCompatibilityTest {

    /**
     * The class is embedded in a connection rather than being a document of its own, so it carries
     * no {@code @XmlRootElement} and has to be read and written through the typed JAXB forms.
     */
    private static TerminalLogConfig unmarshal(String xml) throws Exception {
        Unmarshaller unmarshaller = JAXBContext.newInstance(TerminalLogConfig.class).createUnmarshaller();
        return unmarshaller.unmarshal(
            new javax.xml.transform.stream.StreamSource(new StringReader(xml)),
            TerminalLogConfig.class).getValue();
    }

    @Test
    void readsAConnectionFileWrittenBeforeTheFieldBecameADirectory() throws Exception {
        // The element is still called logFilePath on disk, so existing connections.xml keeps loading.
        TerminalLogConfig config = unmarshal("""
            <terminalLogConfig>
              <enabled>true</enabled>
              <logFilePath>/var/log/kortty/web01.log</logFilePath>
              <maxFileSizeMB>25</maxFileSizeMB>
              <format>JSON</format>
            </terminalLogConfig>
            """);

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getLogDirectoryPath()).isEqualTo("/var/log/kortty/web01.log");
        assertThat(config.getMaxFileSizeMB()).isEqualTo(25);
        assertThat(config.getFormat()).isEqualTo(TerminalLogConfig.LogFormat.JSON);
        // Fields the old file cannot carry fall back to the new defaults.
        assertThat(config.isCompress()).isTrue();
        assertThat(config.isRotateDaily()).isTrue();
        assertThat(config.getRetentionDays()).isEqualTo(TerminalLogConfig.DEFAULT_RETENTION_DAYS);
    }

    @Test
    void keepsWritingTheElementUnderItsOriginalName() throws Exception {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setLogDirectoryPath("/var/log/kortty");

        StringWriter out = new StringWriter();
        Marshaller marshaller = JAXBContext.newInstance(TerminalLogConfig.class).createMarshaller();
        marshaller.marshal(new jakarta.xml.bind.JAXBElement<>(
            new javax.xml.namespace.QName("terminalLogConfig"), TerminalLogConfig.class, config), out);

        // An older korTTY reading this file must still find the setting where it expects it.
        assertThat(out.toString()).contains("<logFilePath>/var/log/kortty</logFilePath>");
        assertThat(out.toString()).doesNotContain("logDirectoryPath");
    }

    @Test
    void mapsAnOldFilePathToTheFolderThatHoldsIt() {
        // A stored file name has to become a directory, or the first log would land beside it.
        assertThat(TerminalLogConfig.resolveDirectory("/var/log/kortty/web01.log"))
            .isEqualTo("/var/log/kortty");
        assertThat(TerminalLogConfig.resolveDirectory("/var/log/kortty/session.json"))
            .isEqualTo("/var/log/kortty");
    }

    @Test
    void neverTurnsAnExtensionlessValueIntoItsParent() {
        // The dangerous case: "~/terminal" has no extension, and taking its parent would point
        // the log directory — and its retention sweep — at the home directory.
        assertThat(TerminalLogConfig.resolveDirectory("/Users/daniel/terminal"))
            .isEqualTo("/Users/daniel/terminal");
        assertThat(TerminalLogConfig.resolveDirectory("/Users/daniel/logs/"))
            .isEqualTo("/Users/daniel/logs/");
    }

    @Test
    void treatsAnExistingDirectoryAsOneNoMatterWhatItIsCalled() throws Exception {
        // A real directory that happens to look like a file name must not be reduced to its parent.
        Path tempDir = Files.createTempDirectory("kortty-log-dir-test");
        Path oddlyNamed = Files.createDirectory(tempDir.resolve("terminal.log"));
        try {
            assertThat(TerminalLogConfig.resolveDirectory(oddlyNamed.toString()))
                .isEqualTo(oddlyNamed.toString());
        } finally {
            Files.deleteIfExists(oddlyNamed);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void treatsABlankValueAsNoChoiceAtAll() {
        assertThat(TerminalLogConfig.resolveDirectory(null)).isNull();
        assertThat(TerminalLogConfig.resolveDirectory("")).isNull();
        assertThat(TerminalLogConfig.resolveDirectory("   ")).isNull();
    }

    @Test
    void copiesRatherThanSharesSoAnEditCannotReachBack() {
        TerminalLogConfig original = new TerminalLogConfig();
        original.setEnabled(true);
        original.setLogDirectoryPath("/original");

        TerminalLogConfig copy = new TerminalLogConfig(original);
        copy.setEnabled(false);
        copy.setLogDirectoryPath("/copy");

        assertThat(original.isEnabled()).isTrue();
        assertThat(original.getLogDirectoryPath()).isEqualTo("/original");
    }
}
