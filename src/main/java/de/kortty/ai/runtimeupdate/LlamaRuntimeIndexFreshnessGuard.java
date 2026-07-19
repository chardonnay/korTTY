package de.kortty.ai.runtimeupdate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable high-water mark that rejects replayed or colliding signed runtime indexes. */
final class LlamaRuntimeIndexFreshnessGuard {

    private static final Set<String> FIELDS = Set.of("generatedAt", "sha256");
    private static final ConcurrentHashMap<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path stateFile;
    private final Path lockFile;

    LlamaRuntimeIndexFreshnessGuard(Path runtimeRoot) {
        Path root = runtimeRoot.toAbsolutePath().normalize();
        this.stateFile = root.resolve("accepted-index-v1.json");
        this.lockFile = root.resolve("accepted-index-v1.lock");
    }

    void accept(LlamaRuntimeIndex index, byte[] verifiedBytes) throws IOException {
        if (index == null || verifiedBytes == null || verifiedBytes.length == 0) {
            throw new IOException("Verified runtime index bytes are required for replay protection.");
        }
        String digest = sha256(verifiedBytes);
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (jvmLock) {
            Files.createDirectories(stateFile.getParent());
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                State previous = read();
                if (previous != null && index.generatedAt().isBefore(previous.generatedAt())) {
                    throw new IOException("Runtime index replay rejected: signed index is older than the last accepted index.");
                }
                if (previous != null && index.generatedAt().equals(previous.generatedAt())
                    && !digest.equals(previous.sha256())) {
                    throw new IOException("Runtime index timestamp collision rejected.");
                }
                if (previous == null || index.generatedAt().isAfter(previous.generatedAt())) {
                    write(new State(index.generatedAt(), digest));
                }
            }
        }
    }

    private State read() throws IOException {
        if (!Files.exists(stateFile)) {
            return null;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("Runtime index replay state is invalid.", error);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Runtime index replay state must be an object.");
        }
        JsonObject object = parsed.getAsJsonObject();
        if (!FIELDS.equals(object.keySet())) {
            throw new IOException("Runtime index replay state has unexpected fields.");
        }
        try {
            Instant generatedAt = Instant.parse(object.get("generatedAt").getAsString());
            String digest = object.get("sha256").getAsString().toLowerCase(java.util.Locale.ROOT);
            if (!digest.matches("[0-9a-f]{64}")) {
                throw new IOException("Runtime index replay digest is invalid.");
            }
            return new State(generatedAt, digest);
        } catch (RuntimeException error) {
            throw new IOException("Runtime index replay state is invalid.", error);
        }
    }

    private void write(State state) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("generatedAt", state.generatedAt().toString());
        object.addProperty("sha256", state.sha256());
        Path partial = stateFile.resolveSibling(stateFile.getFileName() + ".part-" + UUID.randomUUID());
        try {
            Files.writeString(partial, object.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(partial, stateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable.", error);
        }
    }

    private record State(Instant generatedAt, String sha256) { }
}
