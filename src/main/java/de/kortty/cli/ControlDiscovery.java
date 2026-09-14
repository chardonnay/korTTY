package de.kortty.cli;

import de.kortty.control.ControlApiProtocol;
import de.kortty.control.ControlDirectory;
import de.kortty.control.ControlEndpointFile;
import de.kortty.control.EndpointDescriptor;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Finds the running korTTY's control endpoint.
 *
 * <p>Discovery is one file, {@code <configDir>/control/endpoint.json}, on every platform. The server
 * writes it <strong>after</strong> it has bound its listener, so the file's existence is the promise
 * that a client which reads the token will find something listening — and the file is the only place
 * the token lives. There is no {@code --token} flag and no environment variable precisely so a token
 * cannot reach {@code argv}, {@code ps} output, shell history or a CI log.
 *
 * <p>This class is strictly read-only. The CLI never creates, chmods or deletes anything below the
 * configuration directory: a client that repaired a stale endpoint file would be papering over a
 * korTTY that died badly, and a client that created the 0700 directory could create it with the wrong
 * ownership for the server that comes next.
 *
 * <p>Any thread; does filesystem I/O.
 */
public final class ControlDiscovery {

    private ControlDiscovery() {
    }

    /**
     * {@code ~/.kortty}, the same directory {@code KorTTYApplication.getConfigDirectory()} returns.
     *
     * <p>It is recomputed here rather than read from the application class, whose configuration
     * directory is resolved in a static initialiser: loading that class would drag the whole
     * application's static state into a process whose job is one socket round trip.
     */
    public static Path defaultConfigDir() {
        String home = System.getProperty("user.home");
        return Path.of(home == null ? "." : home, ".kortty");
    }

    /**
     * Reads and sanity-checks the endpoint file below {@code configDir}.
     *
     * <p>Every failure is an {@link IOException} — a missing file, a directory where the file should
     * be, a truncated or hand-edited file, an unknown transport, a token that is absent or too short
     * to be the 43-character one the server mints. {@link KorttyCli} maps all of them to the same
     * exit 3 and the same sentence, because from a script's point of view they are one condition: the
     * control API is not reachable.
     *
     * @throws IOException when the file cannot be read or does not describe a usable endpoint
     */
    public static EndpointDescriptor read(Path configDir) throws IOException {
        if (configDir == null) {
            throw new IOException("No korTTY configuration directory was given");
        }
        Path file = ControlEndpointFile.path(configDir.resolve(ControlDirectory.DIRECTORY_NAME));
        EndpointDescriptor endpoint = EndpointDescriptor.readFrom(file);
        String transport = endpoint.transport();
        if (EndpointDescriptor.TRANSPORT_UNIX.equals(transport)) {
            if (endpoint.path() == null || endpoint.path().isBlank()) {
                throw new IOException("Endpoint file " + file + " names no socket path");
            }
        } else if (EndpointDescriptor.TRANSPORT_LOOPBACK.equals(transport)) {
            if (endpoint.port() <= 0 || endpoint.port() > 65_535) {
                throw new IOException("Endpoint file " + file + " names no loopback port");
            }
        } else {
            throw new IOException("Endpoint file " + file + " names the unknown transport '"
                + transport + "'");
        }
        String token = endpoint.token();
        if (token == null || token.strip().length() < ControlApiProtocol.MIN_TOKEN_CHARS) {
            // Never echo what was there: a partial token in a diagnostic is still a leaked secret.
            throw new IOException("Endpoint file " + file + " carries no usable token");
        }
        return endpoint;
    }
}
