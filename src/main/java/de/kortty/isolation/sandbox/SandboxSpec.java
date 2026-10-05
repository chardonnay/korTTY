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
 */
public record SandboxSpec(List<Path> hiddenPaths, List<Path> readableInsideHidden, boolean restrictWrites,
                          List<Path> writablePaths) {

    public SandboxSpec {
        hiddenPaths = hiddenPaths != null ? List.copyOf(hiddenPaths) : List.of();
        readableInsideHidden = readableInsideHidden != null ? List.copyOf(readableInsideHidden) : List.of();
        writablePaths = writablePaths != null ? List.copyOf(writablePaths) : List.of();
    }
}
