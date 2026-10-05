package de.kortty.isolation.sandbox;

import de.kortty.ui.I18n;
import de.kortty.isolation.IsolationLevel;
import de.kortty.isolation.IsolationReport;
import de.kortty.isolation.IsolationRequest;
import de.kortty.isolation.IsolationState;
import de.kortty.isolation.IsolationUnavailableException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares a terminal process that korTTY starts on this computer (a local shell, the native
 * {@code mosh-client}) for the isolation it was asked for. Such a process is a process of its own
 * already, so {@link IsolationLevel#PROCESS} changes nothing about how it starts; {@link IsolationLevel#SANDBOX}
 * starts it inside the operating-system sandbox with korTTY's configuration folder, the SSH and GnuPG keys
 * and the keychains hidden and writing limited to its working folder, the temp folder and a folder of its
 * own.
 *
 * <p>When no sandbox can be used, a session the policy does not force runs without one and reports
 * {@link IsolationState#DEGRADED}; a session the policy forces is not started.
 */
public final class LocalProcessSandbox {

    /**
     * A prepared start.
     *
     * @param command          the command to start, wrapped in the sandbox when there is one
     * @param environment      the environment to start it with
     * @param report           the isolation the session will have
     * @param sessionDirectory the session's own folder to delete when it ends, or null
     */
    public record Prepared(List<String> command, Map<String, String> environment, IsolationReport report,
                           Path sessionDirectory) {
    }

    private LocalProcessSandbox() {
    }

    /**
     * Prepares {@code command}.
     *
     * @param request          what was asked for
     * @param command          the command as it would start without isolation
     * @param environment      its environment
     * @param workingDirectory where it starts, writable inside the sandbox; null for none
     * @param readable         paths inside a hidden folder the process still needs to read (the
     *                         shell-integration folder it starts from), may be empty
     * @throws IsolationUnavailableException when the policy demands a sandbox that cannot be used
     */
    public static Prepared prepare(IsolationRequest request, List<String> command, Map<String, String> environment,
                                   Path workingDirectory, List<Path> readable) throws IOException {
        IsolationRequest req = request != null ? request : IsolationRequest.NONE;
        Map<String, String> env = new HashMap<>(environment != null ? environment : Map.of());
        if (req.level() == IsolationLevel.NONE) {
            return new Prepared(command, env, IsolationReport.NONE, null);
        }
        if (req.level() == IsolationLevel.PROCESS) {
            return new Prepared(command, env,
                new IsolationReport(IsolationState.PROCESS, IsolationLevel.PROCESS, null, null), null);
        }
        SandboxSupport.Availability availability = SandboxSupport.availability();
        if (!availability.available()) {
            String reason = reason(availability);
            if (req.enforced()) {
                throw new IsolationUnavailableException(I18n.get("isolation.error.sandboxRequired", reason));
            }
            return new Prepared(command, env,
                new IsolationReport(IsolationState.DEGRADED, IsolationLevel.SANDBOX, availability.backendId(), reason),
                null);
        }
        SandboxBackend backend = SandboxSupport.backend();
        Path sessionDirectory = SandboxSupport.createSessionDirectory();
        List<Path> writable = new ArrayList<>();
        writable.add(sessionDirectory);
        writable.add(Path.of(System.getProperty("java.io.tmpdir")));
        writable.add(Path.of("/tmp"));
        if (workingDirectory != null) {
            writable.add(workingDirectory);
        }
        SandboxSpec spec = new SandboxSpec(
            SandboxSupport.sensitivePaths(de.kortty.KorTTYApplication.getConfigDirectory()),
            readable, true, writable);
        // The shell's history and the temp files of the programs in it stay with the session.
        env.put("HISTFILE", sessionDirectory.resolve("history").toString());
        env.put("TMPDIR", sessionDirectory.toString());
        env.put("KORTTY_SANDBOX", backend.id());
        return new Prepared(backend.wrap(command, spec), env,
            new IsolationReport(IsolationState.SANDBOXED, IsolationLevel.SANDBOX, backend.id(), null),
            sessionDirectory);
    }

    /**
     * Prepares a session worker (the process an SSH session runs in) for {@code request}: with
     * {@link IsolationLevel#SANDBOX} it starts in the sandbox with korTTY's configuration folder, the
     * SSH and GnuPG keys and the keychains hidden, writing limited to a folder of its own and, where the
     * backend can, connections limited to this computer and {@code outboundPorts}. The worker needs no
     * key file: korTTY signs for it.
     *
     * @throws IsolationUnavailableException when the policy demands a sandbox that cannot be used
     */
    public static Prepared prepareWorker(IsolationRequest request, List<String> command, List<Integer> outboundPorts)
            throws IOException {
        IsolationRequest req = request != null ? request : IsolationRequest.NONE;
        Map<String, String> env = new HashMap<>(System.getenv());
        if (req.level() != IsolationLevel.SANDBOX) {
            return new Prepared(command, env,
                new IsolationReport(IsolationState.PROCESS, req.level(), null, null), null);
        }
        SandboxSupport.Availability availability = SandboxSupport.availability();
        if (!availability.available()) {
            String reason = reason(availability);
            if (req.enforced()) {
                throw new IsolationUnavailableException(I18n.get("isolation.error.sandboxRequired", reason));
            }
            return new Prepared(command, env,
                new IsolationReport(IsolationState.DEGRADED, IsolationLevel.SANDBOX, availability.backendId(), reason),
                null);
        }
        SandboxBackend backend = SandboxSupport.backend();
        Path sessionDirectory = SandboxSupport.createSessionDirectory();
        SandboxSpec spec = new SandboxSpec(
            SandboxSupport.sensitivePaths(de.kortty.KorTTYApplication.getConfigDirectory()),
            List.of(), true, List.of(sessionDirectory), outboundPorts);
        env.put("TMPDIR", sessionDirectory.toString());
        env.put("KORTTY_SANDBOX", backend.id());
        String detail = backend.limitsNetwork() ? null : I18n.get("isolation.sandbox.noNetworkLimit");
        return new Prepared(backend.wrap(command, spec), env,
            new IsolationReport(IsolationState.SANDBOXED, IsolationLevel.SANDBOX, backend.id(), detail),
            sessionDirectory);
    }

    /**
     * The message to refuse a session with before anything starts, when the policy demands a sandbox this
     * computer cannot give; null when the session may start. Runs the self-test the first time.
     */
    public static String refusal(IsolationRequest request) {
        if (request == null || !request.enforced() || request.level() != IsolationLevel.SANDBOX) {
            return null;
        }
        SandboxSupport.Availability availability = SandboxSupport.availability();
        return availability.available() ? null : I18n.get("isolation.error.sandboxRequired", reason(availability));
    }

    /** Why no sandbox can be used, in the user's language. */
    public static String reason(SandboxSupport.Availability availability) {
        String key = switch (availability.status()) {
            case AVAILABLE -> "isolation.sandbox.status.available";
            case UNSUPPORTED_OS -> "isolation.sandbox.status.unsupportedOs";
            case FLATPAK -> "isolation.sandbox.status.flatpak";
            case NOT_INSTALLED -> "isolation.sandbox.status.notInstalled";
            case SELF_TEST_FAILED -> "isolation.sandbox.status.selfTestFailed";
        };
        String text = I18n.get(key, availability.backendId() != null ? availability.backendId() : "");
        return availability.detail() != null && availability.status() == SandboxSupport.Status.SELF_TEST_FAILED
            ? text + " (" + availability.detail() + ")" : text;
    }
}
