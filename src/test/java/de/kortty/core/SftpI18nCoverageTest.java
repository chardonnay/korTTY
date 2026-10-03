package de.kortty.core;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every {@code sftp.*} key the SFTP manager tab and the SFTP session use exists in all 8 bundles,
 * with the placeholders of the English one, and the session's error texts are no longer German
 * literals.
 */
class SftpI18nCoverageTest {

    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final List<Path> SOURCES = List.of(
        Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"),
        Path.of("src/main/java/de/kortty/core/SFTPSession.java"));
    private static final List<String> LOCALES = List.of("", "_de", "_es", "_fr", "_hr", "_it", "_nl", "_pt");
    /** Keys passed to {@code I18n.get}; other "sftp.*" strings (e.g. dialog geometry ids) are not messages. */
    private static final Pattern KEY_USE = Pattern.compile("I18n\\.get\\(\\s*\"(sftp\\.[A-Za-z.]*[A-Za-z])\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everySftpKeyIsTranslatedWithMatchingPlaceholders() throws IOException {
        Set<String> used = new TreeSet<>();
        for (Path source : SOURCES) {
            Matcher matcher = KEY_USE.matcher(read(source));
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
        }
        assertWithMessage("the SFTP sources use sftp.* keys").that(used).contains("sftp.status.disconnected");
        assertWithMessage("the SFTP sources use sftp.* keys").that(used).contains("sftp.error.subsystemRejected");

        Properties english = load("");
        List<String> problems = new ArrayList<>();
        for (String locale : LOCALES) {
            Properties bundle = load(locale);
            for (String key : used) {
                String value = bundle.getProperty(key);
                if (value == null || value.isBlank()) {
                    problems.add("messages" + locale + ": missing " + key);
                } else if (english.getProperty(key) != null
                    && !placeholders(value).equals(placeholders(english.getProperty(key)))) {
                    problems.add("messages" + locale + ": placeholders differ for " + key);
                }
            }
        }
        assertWithMessage("SFTP i18n problems").that(problems).isEmpty();
    }

    @Test
    void germanRemoteLabelIsNotTheTvRemote() throws IOException {
        // "Fernbedienung" is a TV remote control, not the remote server.
        assertThat(load("_de").getProperty("sftp.remoteLabel")).isNotEqualTo("Fernbedienung");
    }

    @Test
    void sessionErrorsAreNoLongerGermanLiterals() throws IOException {
        String session = read(SOURCES.get(1));
        for (String literal : List.of("SFTP-Subsystem", "unbekannter Fehler", "Kein SSH-Key-Pfad",
                "SSH-Key-Datei existiert nicht", "Konnte SSH-Key nicht laden", "Keine KeyPairs",
                "SSH-Key-Authentifizierung fehlgeschlagen")) {
            assertWithMessage("German literal in SFTPSession: " + literal).that(session).doesNotContain(literal);
        }
    }

    /** The source with LF line endings: Windows CI checks sources out with CRLF. */
    private static String read(Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static Properties load(String locale) throws IOException {
        Properties properties = new Properties();
        String text = Files.readString(BUNDLES.resolve("messages" + locale + ".properties"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        try (Reader reader = new StringReader(text)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
