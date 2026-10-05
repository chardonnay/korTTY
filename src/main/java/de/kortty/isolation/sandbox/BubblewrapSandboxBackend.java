package de.kortty.isolation.sandbox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Linux sandbox through bubblewrap ({@code bwrap}), the tool Flatpak uses: the process gets its own
 * mount and process namespace in which the file system is mounted read-only (or read-write when writes
 * are not restricted), the writable folders are mounted read-write on top, the hidden folders are covered
 * with empty in-memory file systems and the paths that must stay readable are mounted back into them.
 * A private {@code /proc} keeps the process from reaching the file system through another process's
 * {@code /proc/<pid>/root}. The host's {@code /dev} stays, because the shell's terminal lives there.
 */
public final class BubblewrapSandboxBackend implements SandboxBackend {

    @Override
    public String id() {
        return "bubblewrap";
    }

    @Override
    public boolean installed() {
        return executable() != null;
    }

    @Override
    public List<String> wrap(List<String> command, SandboxSpec spec) {
        Path bwrap = executable();
        List<String> wrapped = new ArrayList<>();
        wrapped.add(bwrap != null ? bwrap.toString() : "bwrap");
        wrapped.add("--die-with-parent");
        wrapped.add("--unshare-pid");
        wrapped.add(spec.restrictWrites() ? "--ro-bind" : "--bind");
        wrapped.add("/");
        wrapped.add("/");
        wrapped.add("--dev-bind");
        wrapped.add("/dev");
        wrapped.add("/dev");
        wrapped.add("--proc");
        wrapped.add("/proc");
        if (spec.restrictWrites()) {
            for (Path path : spec.writablePaths()) {
                String p = path.toAbsolutePath().normalize().toString();
                if (Files.exists(path)) {
                    wrapped.add("--bind");
                    wrapped.add(p);
                    wrapped.add(p);
                }
            }
        }
        for (Path path : spec.hiddenPaths()) {
            String p = path.toAbsolutePath().normalize().toString();
            if (Files.isDirectory(path)) {
                wrapped.add("--tmpfs");
                wrapped.add(p);
            } else if (Files.exists(path)) {
                wrapped.add("--ro-bind");
                wrapped.add("/dev/null");
                wrapped.add(p);
            }
        }
        for (Path path : spec.readableInsideHidden()) {
            String p = path.toAbsolutePath().normalize().toString();
            if (Files.exists(path)) {
                wrapped.add("--ro-bind");
                wrapped.add(p);
                wrapped.add(p);
            }
        }
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    /** {@code bwrap} on the {@code PATH}, or null. */
    static Path executable() {
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null) {
            return null;
        }
        for (String dir : pathVariable.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, "bwrap");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
