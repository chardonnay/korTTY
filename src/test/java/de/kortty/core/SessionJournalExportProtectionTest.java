package de.kortty.core;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SessionJournalExportProtectionTest {

    private Path tempDir;

    /** A fresh throwaway value per call — no password literal in the source. */
    private static char[] randomKey() {
        return java.util.UUID.randomUUID().toString().toCharArray();
    }

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-export-protection");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void theTargetExtensionFollowsTheProtection() {
        var none = SessionJournalExportProtection.Protection.NONE;
        var password = SessionJournalExportProtection.Protection.password(randomKey());
        var gpg = SessionJournalExportProtection.Protection.gpg("ABCD1234", null);

        assertThat(SessionJournalExportProtection.targetExtension(".pdf", false, none)).isEqualTo(".pdf");
        assertThat(SessionJournalExportProtection.targetExtension(".pdf", false, password)).isEqualTo(".zip");
        assertThat(SessionJournalExportProtection.targetExtension(".zip", true, password)).isEqualTo(".zip");
        assertThat(SessionJournalExportProtection.targetExtension(".md", false, gpg)).isEqualTo(".md.gpg");
        assertThat(SessionJournalExportProtection.targetExtension(".zip", true, gpg)).isEqualTo(".zip.gpg");
    }

    @Test
    void aSinglePdfWithAPasswordIsPackedIntoAnAesZip() throws Exception {
        Path target = tempDir.resolve("journal.zip");
        char[] key = randomKey();
        var protection = SessionJournalExportProtection.Protection.password(key.clone());
        AtomicReference<char[]> passedPassword = new AtomicReference<>();

        String result = SessionJournalExportProtection.export(target, false, "journal.pdf", protection,
            (path, password) -> {
                passedPassword.set(password);
                Files.writeString(path, "%PDF demo");
                return "done";
            }, null);

        assertThat(result).isEqualTo("done");
        assertThat(passedPassword.get()).isNull();
        try (ZipFile zip = new ZipFile(target.toFile(), key)) {
            assertThat(zip.isEncrypted()).isTrue();
            assertThat(zip.getFileHeaders()).hasSize(1);
            assertThat(zip.getFileHeaders().get(0).getFileName()).isEqualTo("journal.pdf");
            Path out = tempDir.resolve("out");
            zip.extractAll(out.toString());
            assertThat(Files.readString(out.resolve("journal.pdf"))).isEqualTo("%PDF demo");
        }
        try (ZipFile wrong = new ZipFile(target.toFile(), randomKey())) {
            assertThrows(ZipException.class, () -> wrong.extractAll(tempDir.resolve("wrong").toString()));
        }
    }

    @Test
    void anArchiveEncryptsItselfWithThePassword() throws Exception {
        Path target = tempDir.resolve("bundle.zip");
        char[] key = randomKey();
        var protection = SessionJournalExportProtection.Protection.password(key.clone());
        AtomicReference<Path> writtenTo = new AtomicReference<>();
        AtomicReference<char[]> passedPassword = new AtomicReference<>();

        SessionJournalExportProtection.export(target, true, "bundle.zip", protection, (path, password) -> {
            writtenTo.set(path);
            passedPassword.set(password);
            return null;
        }, null);

        assertThat(writtenTo.get()).isEqualTo(target);
        assertThat(passedPassword.get()).isEqualTo(key);
    }

    @Test
    void gpgEncryptsThePlainExportAndRemovesIt() throws Exception {
        Path target = tempDir.resolve("journal.md.gpg");
        var protection = SessionJournalExportProtection.Protection.gpg("ABCD1234", "/keys/alice.asc");
        AtomicReference<Path> plainSeen = new AtomicReference<>();
        AtomicReference<String> keySeen = new AtomicReference<>();

        SessionJournalExportProtection.export(target, false, "journal.md", protection,
            (path, password) -> {
                assertThat(password).isNull();
                Files.writeString(path, "# Journal");
                return null;
            },
            (input, output, keyId, publicKeyPath) -> {
                plainSeen.set(input);
                keySeen.set(keyId + "|" + publicKeyPath);
                assertThat(Files.readString(input)).isEqualTo("# Journal");
                Files.writeString(output, "ENCRYPTED");
            });

        assertThat(plainSeen.get().getFileName().toString()).isEqualTo("journal.md");
        assertThat(keySeen.get()).isEqualTo("ABCD1234|/keys/alice.asc");
        assertThat(Files.readString(target)).isEqualTo("ENCRYPTED");
        assertThat(Files.exists(plainSeen.get())).isFalse();
        assertThat(Files.exists(plainSeen.get().getParent())).isFalse();
    }

    @Test
    void aFailedGpgRunStillRemovesThePlainExport() {
        Path target = tempDir.resolve("journal.pdf.gpg");
        var protection = SessionJournalExportProtection.Protection.gpg("NOPE", null);
        AtomicReference<Path> plainSeen = new AtomicReference<>();

        assertThrows(IOException.class, () -> SessionJournalExportProtection.export(target, false, "journal.pdf",
            protection, (path, password) -> {
                plainSeen.set(path);
                Files.writeString(path, "%PDF");
                return null;
            }, (input, output, keyId, publicKeyPath) -> {
                throw new IOException("gpg failed (exit 2): No public key");
            }));

        assertThat(Files.exists(plainSeen.get())).isFalse();
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void thePlainNameDropsTheProtectedExtension() {
        assertThat(SessionJournalExportProtection.plainName(Path.of("/x/web01.zip"), ".zip", ".pdf"))
            .isEqualTo("web01.pdf");
        assertThat(SessionJournalExportProtection.plainName(Path.of("/x/web01.md.gpg"), ".md.gpg", ".md"))
            .isEqualTo("web01.md");
        assertThat(SessionJournalExportProtection.plainName(Path.of("/x/other"), ".zip", ".pdf"))
            .isEqualTo("other.pdf");
    }

    @Test
    void wipeClearsThePassword() {
        char[] value = randomKey();
        SessionJournalExportProtection.Protection.password(value).wipe();
        assertThat(new String(value)).isEqualTo("\0".repeat(value.length));
    }

    /**
     * End to end with the real gpg — only when KORTTY_GPG_E2E_KEY names a public key file of a
     * throwaway key in a temporary GNUPGHOME (never the user's keyring).
     */
    @Test
    void theSystemGpgEncryptsForAKeyFile() throws Exception {
        String keyFile = System.getenv("KORTTY_GPG_E2E_KEY");
        if (keyFile == null || keyFile.isBlank()) {
            throw new org.testng.SkipException("KORTTY_GPG_E2E_KEY not set");
        }
        Path target = tempDir.resolve("journal.md.gpg");
        SessionJournalExportProtection.export(target, false, "journal.md",
            SessionJournalExportProtection.Protection.gpg("e2e@kortty.test", keyFile),
            (path, password) -> {
                Files.writeString(path, "# Journal e2e");
                return null;
            }, SessionJournalExportProtection.SYSTEM_GPG);
        Files.copy(target, Path.of(System.getenv("KORTTY_GPG_E2E_OUT")), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(Files.size(target)).isGreaterThan(0L);
    }
}
