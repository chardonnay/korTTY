package de.kortty.ai.runtimeupdate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Seals and verifies the extracted payload of a SHA-256 verified runtime archive.
 *
 * <p>The manifest is created inside the installer's locked staging transaction, only after the
 * archive size and digest from the signed runtime index have been verified. Every managed launch
 * re-hashes the complete payload and rejects missing, modified, extra, symbolic-link, or special
 * files. This protects the executable and its native libraries from post-install corruption while
 * keeping the very large downloaded archive disposable.
 */
public final class LlamaRuntimePackageIntegrity {

    static final String MANIFEST_FILE = ".kortty-runtime-files-v1.json";
    private static final String DESCRIPTOR_FILE = ".kortty-runtime.json";
    private static final int VERSION = 1;
    private static final int MAX_FILES = 20_000;
    private static final long MAX_MANIFEST_BYTES = 32L * 1024 * 1024;
    private static final Set<String> NON_PAYLOAD_FILES = Set.of(
        MANIFEST_FILE,
        de.kortty.ai.llama.LlamaRuntimeTrustGuard.REVOCATION_MARKER_FILE);

    private LlamaRuntimePackageIntegrity() {
    }

    static void seal(Path installationDirectory, LlamaRuntimePackageDescriptor descriptor)
        throws IOException {
        Path root = normalizedDirectory(installationDirectory);
        Path manifest = root.resolve(MANIFEST_FILE);
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Runtime package contains the reserved integrity-manifest path.");
        }
        List<FileEntry> entries = scanPayload(root);
        if (entries.isEmpty()) {
            throw new IOException("Runtime package payload is empty.");
        }
        String entrypoint = normalizedRelativePath(descriptor.entrypoint());
        if (entries.stream().noneMatch(entry -> entry.path().equals(entrypoint))) {
            throw new IOException("Runtime package integrity manifest does not contain its entrypoint.");
        }

        JsonObject json = new JsonObject();
        json.addProperty("version", VERSION);
        json.addProperty("installationId", descriptor.installationId());
        json.addProperty("archiveSha256", descriptor.sha256());
        JsonArray files = new JsonArray();
        for (FileEntry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.path());
            item.addProperty("size", entry.size());
            item.addProperty("sha256", entry.sha256());
            files.add(item);
        }
        json.add("files", files);
        Files.writeString(
            manifest,
            json.toString() + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
    }

    /** Verifies every payload file for an installation selected for activation. */
    public static void verifyInstallation(LlamaRuntimeInstallation installation) throws IOException {
        if (installation == null) {
            throw new IOException("llama.cpp runtime installation is missing.");
        }
        verify(installation.directory(), installation.descriptor());
    }

    /**
     * Verifies an executable when it resides below a managed {@code runtime/packages/<id>} root.
     * External user-managed llama-server binaries remain supported and are not subject to a
     * korTTY package manifest.
     */
    public static void verifyManagedExecutable(Path executable) throws IOException {
        if (executable == null) {
            throw new IOException("llama-server executable is missing.");
        }
        Path realExecutable = executable.toRealPath();
        Path installationRoot = managedInstallationRoot(realExecutable);
        if (installationRoot == null) {
            return;
        }
        Path descriptorFile = installationRoot.resolve(DESCRIPTOR_FILE);
        if (!Files.isRegularFile(descriptorFile, LinkOption.NOFOLLOW_LINKS)
            || Files.size(descriptorFile) > 64 * 1024) {
            throw new IOException("Managed llama.cpp runtime descriptor is missing or invalid.");
        }
        LlamaRuntimePackageDescriptor descriptor;
        try {
            JsonObject json = JsonParser.parseString(
                Files.readString(descriptorFile, StandardCharsets.UTF_8)).getAsJsonObject();
            descriptor = new LlamaRuntimeIndexCodec().parseDescriptor(json);
        } catch (RuntimeException e) {
            throw new IOException("Managed llama.cpp runtime descriptor is malformed.", e);
        }
        if (!descriptor.installationId().equals(installationRoot.getFileName().toString())) {
            throw new IOException("Managed llama.cpp runtime descriptor does not match its directory.");
        }
        Path describedExecutable = descriptor.resolveEntrypoint(installationRoot);
        if (!Files.isSameFile(realExecutable, describedExecutable)) {
            throw new IOException("Managed llama.cpp runtime executable does not match its signed descriptor.");
        }
        verify(installationRoot, descriptor);
    }

    private static void verify(Path installationDirectory, LlamaRuntimePackageDescriptor descriptor)
        throws IOException {
        Path root = normalizedDirectory(installationDirectory);
        Path manifest = root.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
            || Files.size(manifest) > MAX_MANIFEST_BYTES) {
            throw new IOException("Managed llama.cpp runtime integrity manifest is missing or invalid.");
        }

        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Managed llama.cpp runtime integrity manifest is malformed.", e);
        }
        boolean headerMatches;
        try {
            headerMatches = json.get("version").getAsInt() == VERSION
                && descriptor.installationId().equals(json.get("installationId").getAsString())
                && descriptor.sha256().equalsIgnoreCase(json.get("archiveSha256").getAsString());
        } catch (RuntimeException e) {
            throw new IOException("Managed llama.cpp runtime integrity manifest header is malformed.", e);
        }
        if (!headerMatches) {
            throw new IOException("Managed llama.cpp runtime integrity manifest does not match its descriptor.");
        }

        JsonArray files;
        try {
            files = json.getAsJsonArray("files");
        } catch (RuntimeException e) {
            throw new IOException("Managed llama.cpp runtime integrity file list is malformed.", e);
        }
        if (files == null || files.size() == 0 || files.size() > MAX_FILES) {
            throw new IOException("Managed llama.cpp runtime integrity file list is invalid.");
        }

        Set<String> expected = new HashSet<>();
        for (JsonElement element : files) {
            try {
                JsonObject item = element.getAsJsonObject();
                String relative = normalizedRelativePath(item.get("path").getAsString());
                long expectedSize = item.get("size").getAsLong();
                String expectedSha = item.get("sha256").getAsString().toLowerCase(Locale.ROOT);
                if (expectedSize < 0 || !expectedSha.matches("[0-9a-f]{64}")
                    || NON_PAYLOAD_FILES.contains(relative) || !expected.add(relative)) {
                    throw new IOException("Managed llama.cpp runtime integrity file entry is invalid.");
                }
                Path file = safePayloadPath(root, relative);
                rejectSymlinks(root, file);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) != expectedSize
                    || !expectedSha.equals(sha256(file))) {
                    throw new IOException("Managed llama.cpp runtime payload failed integrity verification: " + relative);
                }
            } catch (IOException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IOException("Managed llama.cpp runtime integrity file entry is malformed.", e);
            }
        }

        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Managed llama.cpp runtime payload contains a symbolic link.");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Managed llama.cpp runtime payload contains a special file.");
                }
                String relative = relativePath(root, path);
                if (!NON_PAYLOAD_FILES.contains(relative)) {
                    actual.add(relative);
                }
            }
        }
        if (!actual.equals(expected)) {
            throw new IOException("Managed llama.cpp runtime payload contains missing or unexpected files.");
        }
    }

    private static List<FileEntry> scanPayload(Path root) throws IOException {
        List<FileEntry> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Runtime package payload contains a symbolic link.");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Runtime package payload contains a special file.");
                }
                String relative = relativePath(root, path);
                if (NON_PAYLOAD_FILES.contains(relative)) {
                    throw new IOException("Runtime package uses a reserved installer metadata path.");
                }
                if (entries.size() >= MAX_FILES) {
                    throw new IOException("Runtime package contains too many payload files.");
                }
                entries.add(new FileEntry(relative, Files.size(path), sha256(path)));
            }
        }
        return List.copyOf(entries);
    }

    private static Path normalizedDirectory(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed llama.cpp runtime directory is missing.");
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Managed llama.cpp runtime directory must not be a symbolic link.");
        }
        return directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Path managedInstallationRoot(Path executable) {
        Path current = executable.getParent();
        while (current != null) {
            Path parent = current.getParent();
            if (parent != null && parent.getFileName() != null
                && "packages".equals(parent.getFileName().toString())) {
                return current;
            }
            current = parent;
        }
        return null;
    }

    private static Path safePayloadPath(Path root, String relative) throws IOException {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Managed llama.cpp runtime manifest path escapes its package.");
        }
        return target;
    }

    private static String normalizedRelativePath(String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw new IOException("Managed llama.cpp runtime manifest contains an invalid path.");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException e) {
            throw new IOException("Managed llama.cpp runtime manifest contains an invalid path.", e);
        }
        if (path.isAbsolute()) {
            throw new IOException("Managed llama.cpp runtime manifest contains an absolute path.");
        }
        String normalized = path.normalize().toString().replace(java.io.File.separatorChar, '/');
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")
            || normalized.startsWith("../") || !normalized.equals(value.replace('\\', '/'))) {
            throw new IOException("Managed llama.cpp runtime manifest path is not canonical.");
        }
        return normalized;
    }

    private static String relativePath(Path root, Path path) throws IOException {
        String value = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        return normalizedRelativePath(value);
    }

    private static void rejectSymlinks(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Managed llama.cpp runtime payload contains a symbolic link.");
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        try (InputStream raw = Files.newInputStream(path);
             DigestInputStream input = new DigestInputStream(raw, digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private record FileEntry(String path, long size, String sha256) {
    }
}
