package de.kortty.control;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, replaces and removes the 0600 discovery file {@code ~/.kortty/control/endpoint.json}.
 *
 * <p>Any thread, never the JavaFX application thread.
 *
 * <p>The mode is set <strong>at creation time</strong> through a file attribute, never by a chmod
 * afterwards, so the token is never readable through a 0644 window — the same idiom the AI runtime
 * managers use for their API keys. A previous file is unlinked first rather than reused, so a
 * leftover with looser permissions cannot survive a restart.
 */
public final class ControlEndpointFile {

    private static final Logger LOG = LoggerFactory.getLogger(ControlEndpointFile.class);

    /** The discovery file name inside the control directory. */
    public static final String FILE_NAME = "endpoint.json";

    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private ControlEndpointFile() {
    }

    /** Where the discovery file lives inside {@code controlDir}. */
    public static Path path(Path controlDir) {
        Objects.requireNonNull(controlDir, "controlDir");
        return controlDir.resolve(FILE_NAME);
    }

    /**
     * Replaces the discovery file with {@code descriptor}, mode 0600.
     *
     * <p>Call this <strong>after</strong> the listener is bound: the file's existence is the promise
     * that a client which reads the token will find something listening.
     */
    public static void write(Path controlDir, EndpointDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        Path file = path(controlDir);
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
            Files.writeString(file, descriptor.toJson(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (UnsupportedOperationException e) {
            Files.writeString(file, descriptor.toJson(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            // Another thread raced us between the delete and the create; the last writer wins.
            Files.writeString(file, descriptor.toJson(), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    /**
     * Removes the discovery file; idempotent and never throws.
     *
     * <p>{@code Runtime.halt(0)} means JVM shutdown hooks never run in korTTY, so this is called from
     * the server's bounded {@code close()} and from every start-failure path, never from
     * {@code deleteOnExit}.
     */
    public static void delete(Path controlDir) {
        if (controlDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(path(controlDir));
        } catch (IOException | RuntimeException e) {
            LOG.debug("control-api: cannot remove {}", path(controlDir), e);
        }
    }
}
