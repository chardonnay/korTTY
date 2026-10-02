package de.kortty.core;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayInputStream;
import java.io.CharConversionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * The shared load-failure rules of korTTY's data stores: only a file whose content does not parse
 * is moved aside; a file that cannot be READ (a virus scanner, a lock, a network home directory
 * that is briefly gone) is valid as far as anyone knows, so it stays in place and saving is
 * blocked instead of replacing it with an empty store.
 */
class StoreFileGuardTest {

    @XmlRootElement(name = "sample")
    static class Sample {
        public String value;
    }

    private static final JAXBContext SAMPLE_CONTEXT;
    static {
        try {
            SAMPLE_CONTEXT = JAXBContext.newInstance(Sample.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final StoreFileGuard.Parser<Sample> JAXB_PARSER = content ->
        (Sample) SAMPLE_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(content));

    private Path dir;

    @BeforeMethod
    void createDir() throws Exception {
        dir = Files.createTempDirectory("kortty-store-guard");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws Exception {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isDirectory(path) && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                }
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void validFileIsParsed() throws Exception {
        Path file = Files.writeString(dir.resolve("sample.xml"), "<sample><value>kept</value></sample>");
        StoreFileGuard guard = new StoreFileGuard(file);

        Optional<Sample> loaded = guard.read(JAXB_PARSER);

        assertThat(loaded.orElseThrow().value).isEqualTo("kept");
        assertThat(guard.isSaveBlocked()).isFalse();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        guard.ensureWritable();
    }

    @Test
    void corruptFileIsMovedAsideAndTheBackupPathReturned() throws Exception {
        byte[] truncated = "<sample><value>cut off".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve("sample.xml"), truncated);
        StoreFileGuard guard = new StoreFileGuard(file);

        Optional<Sample> loaded = guard.read(JAXB_PARSER);

        assertThat(loaded).isEmpty();
        Path backup = guard.getLoadFailureBackup().orElseThrow();
        assertThat(backup.getFileName().toString()).matches("sample\\.xml\\.corrupt-\\d{8}-\\d{6}");
        assertThat(Files.readAllBytes(backup)).isEqualTo(truncated);
        assertThat(Files.exists(file)).isFalse();
        assertThat(guard.isSaveBlocked()).isFalse();
        guard.ensureWritable();
    }

    @Test
    void bytesThatAreNotUtf8OrNotXmlAreTreatedAsCorruption() throws Exception {
        byte[] latin1 = "<sample><value>Grüße</value></sample>".getBytes(StandardCharsets.ISO_8859_1);
        byte[] empty = new byte[0];
        byte[] otherRoot = "<connections/>".getBytes(StandardCharsets.UTF_8);
        for (byte[] content : List.of(latin1, empty, otherRoot)) {
            Path file = Files.write(dir.resolve("sample.xml"), content);
            StoreFileGuard guard = new StoreFileGuard(file);

            assertThat(guard.read(JAXB_PARSER)).isEmpty();

            assertThat(guard.isSaveBlocked()).isFalse();
            assertThat(Files.readAllBytes(guard.getLoadFailureBackup().orElseThrow())).isEqualTo(content);
            assertThat(Files.exists(file)).isFalse();
        }
    }

    @Test
    void quarantineOrBlockReturnsTheMovedPath() throws Exception {
        Path file = Files.writeString(dir.resolve("sample.xml"), "garbage");
        StoreFileGuard guard = new StoreFileGuard(file);

        Path backup = guard.quarantineOrBlock().orElseThrow();

        assertThat(Files.readString(backup)).isEqualTo("garbage");
        assertThat(guard.getLoadFailureBackup()).hasValue(backup);
        assertThat(guard.isSaveBlocked()).isFalse();
    }

    @Test
    void whenTheCorruptFileCannotBeMovedAsideSavingIsBlocked() throws Exception {
        requireEnforcedPosixPermissions();
        Path file = Files.writeString(dir.resolve("sample.xml"), "<sample>");
        StoreFileGuard guard = new StoreFileGuard(file);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));

        Exception failure = expectThrows(Exception.class, () -> guard.read(JAXB_PARSER));

        assertThat(StoreFileGuard.isParseFailure(failure)).isTrue();
        assertThat(guard.quarantineOrBlock()).isEmpty();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        assertThat(guard.isSaveBlocked()).isTrue();
        IllegalStateException refused = expectThrows(IllegalStateException.class, guard::ensureWritable);
        assertThat(refused).hasMessageThat().contains(file.toString());
        assertThat(Files.readString(file)).isEqualTo("<sample>");
    }

    @Test
    void unreadableFileWithoutAParseErrorStaysInPlaceAndBlocksSaving() throws Exception {
        requireEnforcedPosixPermissions();
        String valid = "<sample><value>valid</value></sample>";
        Path file = Files.writeString(dir.resolve("sample.xml"), valid);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        StoreFileGuard guard = new StoreFileGuard(file);

        expectThrows(AccessDeniedException.class, () -> guard.read(JAXB_PARSER));

        assertThat(guard.isSaveBlocked()).isTrue();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        expectThrows(IllegalStateException.class, guard::ensureWritable);
        assertThat(siblings()).containsExactly("sample.xml");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.readString(file)).isEqualTo(valid);
    }

    @Test
    void readFailureOnAnyPlatformIsNotTreatedAsCorruption() throws Exception {
        // A directory where the file should be: reading it fails with an IOException everywhere
        // (also as root and on Windows), without any parse error involved.
        Path file = Files.createDirectory(dir.resolve("sample.xml"));
        StoreFileGuard guard = new StoreFileGuard(file);

        expectThrows(IOException.class, () -> guard.read(JAXB_PARSER));

        assertThat(guard.isSaveBlocked()).isTrue();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        assertThat(Files.isDirectory(file)).isTrue();
        assertThat(siblings()).containsExactly("sample.xml");
    }

    @Test
    void parserFailureCausedByIoIsNotTreatedAsCorruption() throws Exception {
        Path file = Files.writeString(dir.resolve("sample.xml"), "<sample/>");
        StoreFileGuard guard = new StoreFileGuard(file);

        expectThrows(UnmarshalException.class, () -> guard.read(content -> {
            throw new UnmarshalException(new IOException("Stream closed"));
        }));

        assertThat(guard.isSaveBlocked()).isTrue();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        assertThat(Files.readString(file)).isEqualTo("<sample/>");
    }

    @Test
    void unexpectedFailureWhileParsingBlocksSavingInsteadOfQuarantining() throws Exception {
        Path file = Files.writeString(dir.resolve("sample.xml"), "<sample/>");
        StoreFileGuard guard = new StoreFileGuard(file);

        expectThrows(IllegalStateException.class, () -> guard.read(content -> {
            throw new IllegalStateException("bug in the store");
        }));

        assertThat(guard.isSaveBlocked()).isTrue();
        assertThat(guard.getLoadFailureBackup()).isEmpty();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void beginLoadLiftsTheBlockButKeepsTheLastBackupPath() throws Exception {
        Path file = Files.writeString(dir.resolve("sample.xml"), "<sample>");
        StoreFileGuard guard = new StoreFileGuard(file);
        guard.read(JAXB_PARSER);
        Path backup = guard.getLoadFailureBackup().orElseThrow();

        // A second load (themes.xml is loaded twice at startup) finds the fresh file and succeeds.
        Files.writeString(file, "<sample><value>fresh</value></sample>");
        guard.beginLoad();
        assertThat(guard.read(JAXB_PARSER).orElseThrow().value).isEqualTo("fresh");
        assertThat(guard.getLoadFailureBackup()).hasValue(backup);

        Files.createDirectory(dir.resolve("blocked.xml"));
        StoreFileGuard blocked = new StoreFileGuard(dir.resolve("blocked.xml"));
        expectThrows(IOException.class, () -> blocked.read(JAXB_PARSER));
        assertThat(blocked.isSaveBlocked()).isTrue();
        blocked.beginLoad();
        assertThat(blocked.isSaveBlocked()).isFalse();
    }

    @Test
    void onlyMalformedContentCountsAsAParseFailure() {
        assertThat(StoreFileGuard.isParseFailure(
            new UnmarshalException(new SAXParseException("Premature end of file.", null)))).isTrue();
        // Xerces reports bytes that are not valid UTF-8 as a CharConversionException.
        assertThat(StoreFileGuard.isParseFailure(
            new UnmarshalException(new CharConversionException("Invalid byte 1 of 1-byte UTF-8 sequence.")))).isTrue();
        assertThat(StoreFileGuard.isParseFailure(new SAXParseException("Content is not allowed in prolog.", null)))
            .isTrue();
        assertThat(StoreFileGuard.isParseFailure(new IllegalArgumentException("Malformed Unicode escape"))).isTrue();

        assertThat(StoreFileGuard.isParseFailure(new UnmarshalException(new AccessDeniedException("sample.xml"))))
            .isFalse();
        assertThat(StoreFileGuard.isParseFailure(new IOException("The network name cannot be found"))).isFalse();
        assertThat(StoreFileGuard.isParseFailure(new NullPointerException())).isFalse();
    }

    private List<String> siblings() throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private void requireEnforcedPosixPermissions() {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX permissions that apply to the current user");
        }
    }
}
