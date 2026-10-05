package de.kortty.isolation.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * What a sandboxed process may touch. Everything not named stays readable, so programs, libraries,
 * terminfo and the user's dotfiles keep working; the sandbox takes away what matters to korTTY.
 *
 * @param hiddenPaths          folders and files the process can neither read nor write: korTTY's
 *                             configuration folder (connections, credentials, {@code master.key}),
 *                             {@code ~/.ssh}, {@code ~/.gnupg}, the keychains
 * @param readableInsideHidden paths below a hidden one that stay readable, such as the one
 *                             shell-integration folder this shell starts from
 * @param restrictWrites       whether writing is limited to {@code writablePaths} (plus devices)
 * @param writablePaths        where the process may write when {@code restrictWrites} is set
 * @param outboundPorts        the remote TCP ports the process may connect to (plus this computer and
 *                             name resolution), or null for no limit. Backends that cannot limit the
 *                             network ignore it ({@link SandboxBackend#limitsNetwork()}).
 * @param outboundUdpPorts     the remote UDP ports the process may send to (Mosh), used with
 *                             {@code outboundPorts}; empty for none
 */
public record SandboxSpec(List<Path> hiddenPaths, List<Path> readableInsideHidden, boolean restrictWrites,
                          List<Path> writablePaths, List<Integer> outboundPorts, List<Integer> outboundUdpPorts) {

    public SandboxSpec {
        hiddenPaths = hiddenPaths != null ? List.copyOf(hiddenPaths) : List.of();
        readableInsideHidden = readableInsideHidden != null ? List.copyOf(readableInsideHidden) : List.of();
        writablePaths = writablePaths != null ? List.copyOf(writablePaths) : List.of();
        outboundPorts = outboundPorts != null ? List.copyOf(outboundPorts) : null;
        outboundUdpPorts = outboundUdpPorts != null ? List.copyOf(outboundUdpPorts) : List.of();
    }

    /** A spec that limits TCP connections only. */
    public SandboxSpec(List<Path> hiddenPaths, List<Path> readableInsideHidden, boolean restrictWrites,
                       List<Path> writablePaths, List<Integer> outboundPorts) {
        this(hiddenPaths, readableInsideHidden, restrictWrites, writablePaths, outboundPorts, List.of());
    }

    /** A spec without a network limit. */
    public SandboxSpec(List<Path> hiddenPaths, List<Path> readableInsideHidden, boolean restrictWrites,
                       List<Path> writablePaths) {
        this(hiddenPaths, readableInsideHidden, restrictWrites, writablePaths, null, List.of());
    }
}
