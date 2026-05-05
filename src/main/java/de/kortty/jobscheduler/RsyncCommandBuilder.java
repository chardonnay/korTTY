package de.kortty.jobscheduler;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RsyncCommandBuilder {

    public BuiltRsyncCommand build(RsyncCommandInput input) {
        JobAction action = input.action();
        List<String> sources = normalizedSources(action);
        String targetRoot = requireNonBlank(input.effectiveTargetRoot(), "Rsync target root is required.");
        List<String> args = new ArrayList<>();
        args.add(requireNonBlank(input.rsyncBinary(), "rsync executable is required."));
        args.add("-a");
        args.add("--itemize-changes");
        if (action.isRsyncDeleteEnabled()) {
            args.add("--delete");
        }
        args.add("-e");
        args.add(sshCommand(input));
        if (action.isUseSudo()) {
            args.add("--rsync-path=sudo -n rsync");
        }
        if (action.getRsyncDirection() == RsyncDirection.DOWNLOAD) {
            sources.stream()
                .map(source -> remoteSpec(input.connection(), source))
                .forEach(args::add);
            args.add(targetRoot);
        } else {
            args.addAll(sources);
            args.add(remoteSpec(input.connection(), targetRoot));
        }
        return new BuiltRsyncCommand(List.copyOf(args), displayCommand(args));
    }

    private String sshCommand(RsyncCommandInput input) {
        List<String> args = new ArrayList<>();
        args.add(requireNonBlank(input.sshBinary(), "ssh executable is required."));
        args.add("-p");
        args.add(Integer.toString(Math.max(1, input.connection().getPort())));
        args.add("-o");
        args.add("BatchMode=no");
        args.add("-o");
        args.add("NumberOfPasswordPrompts=1");
        args.add("-o");
        args.add("ConnectTimeout=" + Math.max(1, input.connection().getConnectionTimeoutSeconds()));
        if (input.hostKeyVerificationDisabled()) {
            args.add("-o");
            args.add("StrictHostKeyChecking=no");
            args.add("-o");
            args.add("UserKnownHostsFile=/dev/null");
        } else {
            args.add("-o");
            args.add("StrictHostKeyChecking=yes");
            args.add("-o");
            args.add("UserKnownHostsFile=" + requirePath(input.knownHostsFile(), "known_hosts file is required."));
        }
        if (input.authMethod() == AuthMethod.PASSWORD) {
            args.add("-o");
            args.add("PreferredAuthentications=password,keyboard-interactive");
            args.add("-o");
            args.add("PubkeyAuthentication=no");
        }
        input.privateKeyPath().ifPresent(path -> {
            args.add("-i");
            args.add(path.toString());
            args.add("-o");
            args.add("IdentitiesOnly=yes");
        });
        return displayCommand(args);
    }

    private List<String> normalizedSources(JobAction action) {
        List<String> sources = action.getRsyncSourcePaths().stream()
            .map(value -> value != null ? value.trim() : "")
            .filter(value -> !value.isEmpty())
            .toList();
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("At least one Rsync source path is required.");
        }
        return sources;
    }

    private String remoteSpec(ServerConnection connection, String path) {
        String username = requireNonBlank(connection.getUsername(), "Server username is required.");
        String host = requireNonBlank(connection.getHost(), "Server host is required.");
        return username + "@" + remoteHost(host) + ":" + ShellEscaper.quote(path);
    }

    private String remoteHost(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private String displayCommand(List<String> args) {
        return args.stream()
            .map(ShellEscaper::quote)
            .reduce((left, right) -> left + " " + right)
            .orElse("");
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String requirePath(Path path, String message) {
        if (path == null) {
            throw new IllegalArgumentException(message);
        }
        return path.toString();
    }

    public record RsyncCommandInput(
        String rsyncBinary,
        String sshBinary,
        ServerConnection connection,
        JobAction action,
        Path knownHostsFile,
        java.util.Optional<Path> privateKeyPath,
        AuthMethod authMethod,
        boolean hostKeyVerificationDisabled,
        String effectiveTargetRoot) {
    }

    public record BuiltRsyncCommand(List<String> arguments, String displayCommand) {
    }
}
