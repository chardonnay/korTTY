package de.kortty.ai.huggingface;

import de.kortty.security.EncryptionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Stores the optional Hub token encrypted with korTTY's master password. */
public final class HuggingFaceTokenStore {

    private static final String FILE_NAME = "huggingface-token.enc";
    private static final int MAX_ENCRYPTED_BYTES = 64 * 1024;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private final Path tokenFile;
    private final EncryptionService encryptionService;

    public HuggingFaceTokenStore(Path configDirectory) {
        this(configDirectory, new EncryptionService());
    }

    public HuggingFaceTokenStore(Path configDirectory, EncryptionService encryptionService) {
        if (configDirectory == null) {
            throw new IllegalArgumentException("Configuration directory is required.");
        }
        this.tokenFile = configDirectory.resolve("llm").resolve(FILE_NAME);
        this.encryptionService = java.util.Objects.requireNonNull(encryptionService, "encryptionService");
    }

    public synchronized void store(String token, char[] masterPassword) throws Exception {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Hugging Face token is required.");
        }
        if (token.length() > 16_384 || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Hugging Face token is invalid.");
        }
        requireMasterPassword(masterPassword);
        String encrypted = encryptionService.encryptPassword(token.trim(), masterPassword);
        Files.createDirectories(tokenFile.getParent());
        Path partial = tokenFile.resolveSibling(tokenFile.getFileName() + ".part");
        try {
            Files.writeString(partial, encrypted, StandardCharsets.UTF_8);
            restrictPermissions(partial);
            try {
                Files.move(partial, tokenFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(tokenFile);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    public synchronized Optional<String> load(char[] masterPassword) throws Exception {
        if (!Files.isRegularFile(tokenFile)) {
            return Optional.empty();
        }
        requireMasterPassword(masterPassword);
        long size = Files.size(tokenFile);
        if (size <= 0 || size > MAX_ENCRYPTED_BYTES) {
            throw new IOException("Encrypted Hugging Face token file is invalid.");
        }
        String encrypted = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        String token = encryptionService.decryptPassword(encrypted, masterPassword);
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(tokenFile);
        Files.deleteIfExists(tokenFile.resolveSibling(tokenFile.getFileName() + ".part"));
    }

    public boolean isConfigured() {
        return Files.isRegularFile(tokenFile);
    }

    /** Creates a provider that obtains a fresh master-password copy for each HTTP request. */
    public HuggingFaceTokenProvider provider(Supplier<char[]> masterPasswordSupplier) {
        java.util.Objects.requireNonNull(masterPasswordSupplier, "masterPasswordSupplier");
        return () -> {
            char[] password = masterPasswordSupplier.get();
            if (password == null) {
                return Optional.empty();
            }
            try {
                return load(password);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to unlock the Hugging Face token.", e);
            } finally {
                Arrays.fill(password, '\0');
            }
        };
    }

    Path tokenFile() {
        return tokenFile;
    }

    private static void requireMasterPassword(char[] masterPassword) {
        if (masterPassword == null || masterPassword.length == 0) {
            throw new IllegalArgumentException("Master password is required.");
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL protection is inherited from the user's configuration directory.
        }
    }
}
