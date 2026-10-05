package de.kortty.isolation.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The macOS sandbox through {@code /usr/bin/sandbox-exec} and a generated SBPL profile. Apple marks the
 * tool as deprecated, but it is present on every supported release and is what the system's own
 * services are confined with. {@code sandbox-exec} applies the profile and then executes the command,
 * so the shell keeps the process id the terminal knows.
 *
 * <p>Rules in an SBPL profile are matched last to first, so the profile allows everything, then takes
 * away writing (when asked), then hands back the writable folders, then hides the sensitive paths and
 * finally gives back what must stay readable inside them.
 */
public final class MacSandboxBackend implements SandboxBackend {

    static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    @Override
    public String id() {
        return "sandbox-exec";
    }

    @Override
    public boolean installed() {
        return Files.isExecutable(SANDBOX_EXEC);
    }

    @Override
    public boolean limitsNetwork() {
        return true;
    }

    @Override
    public List<String> wrap(List<String> command, SandboxSpec spec) {
        List<String> wrapped = new ArrayList<>();
        wrapped.add(SANDBOX_EXEC.toString());
        wrapped.add("-p");
        wrapped.add(profile(spec));
        wrapped.addAll(command);
        return wrapped;
    }

    /** The SBPL profile for {@code spec}. */
    static String profile(SandboxSpec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(allow default)\n");
        if (spec.restrictWrites()) {
            sb.append("(deny file-write*)\n");
            sb.append("(allow file-write*");
            sb.append(" (subpath \"/dev\")");
            for (Path path : spec.writablePaths()) {
                for (String variant : SandboxPaths.variants(path)) {
                    sb.append(" (subpath ").append(quote(variant)).append(')');
                }
            }
            sb.append(")\n");
        }
        if (spec.outboundPorts() != null) {
            // The sandbox filters by port only (a host other than localhost cannot be named), so the
            // process reaches this computer, name resolution and the given ports, nothing else.
            sb.append("(deny network-outbound)\n");
            sb.append("(allow network-outbound (remote ip \"localhost:*\")");
            for (Integer port : spec.outboundPorts()) {
                if (port != null && port > 0 && port < 65536) {
                    sb.append(" (remote tcp \"*:").append(port).append("\")");
                }
            }
            sb.append(" (literal \"/private/var/run/mDNSResponder\"))\n");
        }
        if (!spec.hiddenPaths().isEmpty()) {
            sb.append("(deny file-read* file-write*");
            for (Path path : spec.hiddenPaths()) {
                for (String variant : SandboxPaths.variants(path)) {
                    sb.append(" (subpath ").append(quote(variant)).append(')');
                }
            }
            sb.append(")\n");
        }
        if (!spec.readableInsideHidden().isEmpty()) {
            sb.append("(allow file-read*");
            for (Path path : spec.readableInsideHidden()) {
                for (String variant : SandboxPaths.variants(path)) {
                    sb.append(" (subpath ").append(quote(variant)).append(')');
                }
            }
            sb.append(")\n");
            // The folders above a readable path must be listable far enough to reach it.
            sb.append("(allow file-read-metadata");
            for (Path path : spec.readableInsideHidden()) {
                for (String variant : SandboxPaths.variants(path)) {
                    Path parent = Path.of(variant).getParent();
                    while (parent != null && parent.getNameCount() > 0) {
                        sb.append(" (literal ").append(quote(parent.toString())).append(')');
                        parent = parent.getParent();
                    }
                }
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    /** {@code value} as an SBPL string literal. */
    static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
