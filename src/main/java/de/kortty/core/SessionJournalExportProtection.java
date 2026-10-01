package de.kortty.core;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Encrypts a session journal export: an AES-256 password ZIP — for an archive directly, for a
 * single PDF or Markdown file by packing it into one — or GPG public-key encryption of whatever
 * the export produced, via the installed {@code gpg}. The plaintext of a protected export only
 * ever exists in a private temporary folder that is removed afterwards.
 */
public final class SessionJournalExportProtection {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalExportProtection.class);

    /** How an export is protected; at most one of password and GPG key is set. */
    public record Protection(char[] password, String gpgKeyId, String gpgPublicKeyPath) {

        public static final Protection NONE = new Protection(null, null, null);

        public static Protection password(char[] password) {
            return new Protection(password, null, null);
        }

        public static Protection gpg(String keyId, String publicKeyPath) {
            return new Protection(null, keyId, publicKeyPath);
        }

        public boolean usesPassword() {
            return password != null && password.length > 0;
        }

        public boolean usesGpg() {
            return gpgKeyId != null && !gpgKeyId.isBlank();
        }

        /** Overwrites the password in memory. */
        public void wipe() {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
            }
        }
    }

    /** Encrypts {@code input} into {@code output} for one recipient; the seam tests replace. */
    @FunctionalInterface
    public interface GpgEncryptor {
        void encrypt(Path input, Path output, String keyId, String publicKeyPath) throws IOException;
    }

    /** The system's {@code gpg}: non-interactive, the key trusted as chosen by the user. */
    public static final GpgEncryptor SYSTEM_GPG = SessionJournalExportProtection::encryptWithSystemGpg;

    private SessionJournalExportProtection() {
    }

    /**
     * The file extension the protected export ends in: {@code .zip} for a password-protected single
     * file, {@code <extension>.gpg} for GPG, otherwise the unprotected one.
     *
     * @param plainExtension what the export writes without protection (".pdf", ".md", ".zip")
     */
    public static String targetExtension(String plainExtension, boolean archive, Protection protection) {
        if (protection != null && protection.usesGpg()) {
            return plainExtension + ".gpg";
        }
        if (protection != null && protection.usesPassword() && !archive) {
            return ".zip";
        }
        return plainExtension;
    }

    /**
     * Runs the export and protects its result.
     *
     * @param archive    whether the export itself writes a ZIP (HTML bundle or several journals)
     * @param exporter   writes the export to a path; gets the password only when the archive
     *                   itself is encrypted
     * @param plainName  file name of the unprotected export inside a password ZIP, e.g. "journal.pdf"
     */
    public static <T> T export(Path target, boolean archive, String plainName, Protection protection,
                               Exporter<T> exporter, GpgEncryptor gpg) throws IOException {
        Protection effective = protection != null ? protection : Protection.NONE;
        if (effective.usesGpg()) {
            Path work = privateTempDir();
            try {
                Path plain = work.resolve(plainName);
                T result = exporter.export(plain, null);
                (gpg != null ? gpg : SYSTEM_GPG).encrypt(plain, target, effective.gpgKeyId(), effective.gpgPublicKeyPath());
                return result;
            } finally {
                deleteRecursively(work);
            }
        }
        if (effective.usesPassword() && !archive) {
            Path work = privateTempDir();
            try {
                Path plain = work.resolve(plainName);
                T result = exporter.export(plain, null);
                zipEncrypted(plain, target, effective.password());
                return result;
            } finally {
                deleteRecursively(work);
            }
        }
        return exporter.export(target, effective.usesPassword() ? effective.password() : null);
    }

    /** One export run; {@code password} is non-null only when the archive encrypts itself. */
    @FunctionalInterface
    public interface Exporter<T> {
        T export(Path target, char[] password) throws IOException;
    }

    /** Packs one file into a new AES-256 encrypted ZIP. */
    static void zipEncrypted(Path file, Path targetZip, char[] password) throws IOException {
        Files.deleteIfExists(targetZip);
        ZipParameters parameters = new ZipParameters();
        parameters.setEncryptFiles(true);
        parameters.setEncryptionMethod(EncryptionMethod.AES);
        parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        try (ZipFile zip = new ZipFile(targetZip.toFile(), password)) {
            zip.addFile(file.toFile(), parameters);
        }
    }

    /** Gpg4win's install folders; the PATH and the usual Unix/Homebrew folders are searched first. */
    private static final List<String> WINDOWS_GPG = List.of(
        "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe",
        "C:\\Program Files\\GnuPG\\bin\\gpg.exe");

    /**
     * The absolute path of the installed {@code gpg}, or empty — run by absolute path so a
     * {@code gpg} planted in the working directory or an early PATH entry is never picked up
     * implicitly by the process launcher.
     */
    public static java.util.Optional<String> resolveGpg() {
        java.util.Optional<String> found = AiCliProviderRegistry.findExecutable("gpg");
        if (found.isPresent()) {
            return found.map(path -> Path.of(path).toAbsolutePath().toString());
        }
        return WINDOWS_GPG.stream().filter(path -> Files.isExecutable(Path.of(path))).findFirst();
    }

    /** Whether a usable {@code gpg} is installed. */
    public static boolean isGpgAvailable() {
        java.util.Optional<String> gpg = resolveGpg();
        if (gpg.isEmpty()) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(gpg.get(), "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void encryptWithSystemGpg(Path input, Path output, String keyId, String publicKeyPath)
            throws IOException {
        String gpg = resolveGpg().orElseThrow(() -> new IOException("gpg is not installed or not found"));
        List<String> command = new ArrayList<>(List.of(gpg, "--batch", "--yes", "--trust-model", "always",
            "--encrypt"));
        if (publicKeyPath != null && !publicKeyPath.isBlank() && Files.isRegularFile(Path.of(publicKeyPath))) {
            // the key file korTTY manages works even when the key is not in the gpg keyring
            command.addAll(List.of("--recipient-file", publicKeyPath));
        } else {
            command.addAll(List.of("--recipient", keyId));
        }
        command.addAll(List.of("--output", output.toString(), input.toString()));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("gpg is not installed or not on the PATH", e);
        }
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("gpg did not finish within 120 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("GPG encryption was interrupted", e);
        }
        if (process.exitValue() != 0) {
            Files.deleteIfExists(output);
            throw new IOException("gpg failed (exit " + process.exitValue() + ")" + (log.isEmpty() ? "" : ": " + log));
        }
        logger.info("Session journal export encrypted with GPG for key {}: {}", keyId, output.getFileName());
    }

    private static Path privateTempDir() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("kortty-journal-export",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory("kortty-journal-export");
    }

    private static void deleteRecursively(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.debug("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.debug("Could not clean up {}: {}", dir, e.getMessage());
        }
    }

    /** Name of the plain export inside a password ZIP: the target's name with the plain extension. */
    public static String plainName(Path target, String protectedExtension, String plainExtension) {
        String name = target.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(protectedExtension.toLowerCase(Locale.ROOT))) {
            name = name.substring(0, name.length() - protectedExtension.length());
        }
        return (name.isBlank() ? "session-journal" : name) + plainExtension;
    }
}
