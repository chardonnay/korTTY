package de.kortty.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.kortty.control.ControlApiProtocol;
import de.kortty.control.EndpointDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The {@code kortty-cli} entry point.
 *
 * <p>The launcher is {@code kortty-cli} and not {@code kortty}: {@code /usr/bin/kortty} is already the
 * pacman GUI symlink, and on APFS and NTFS {@code kortty.exe} <em>is</em> {@code korTTY.exe}, a
 * collision jpackage's case-sensitive duplicate check would not catch.
 *
 * <p>{@link #main} is the only {@code System.exit} caller in this package and {@link #run} returns the
 * code instead, so every documented exit code is asserted inside the shared test JVM without forking
 * a process — the same idiom the WebView JIT smoke uses. No test in this repository calls a
 * {@code main()}.
 *
 * <p>Nothing here loads JavaFX, {@code de.kortty.Launcher} or {@code de.kortty.KorTTYApplication}: a
 * CLI whose job is one socket round trip must not pay for a toolkit, and the launcher may relaunch
 * the JVM.
 *
 * <p>Single-threaded; {@link ControlClient} owns the one daemon watchdog thread a deadline needs.
 */
public final class KorttyCli {

    /** The server answered with a result. */
    public static final int EXIT_OK = 0;

    /** The request failed for a reason the caller may not be able to fix. */
    public static final int EXIT_FAILED = 1;

    /** A usage or syntax error, diagnosed locally or by the server. */
    public static final int EXIT_SYNTAX = 2;

    /** The control API could not be reached, or refused this client. */
    public static final int EXIT_UNREACHABLE = 3;

    /** A wait expired, on the server or against the client deadline. */
    public static final int EXIT_TIMEOUT = 4;

    /** The name korTTY logs this client under when it authenticates. */
    static final String CLIENT_NAME = "kortty-cli";

    /**
     * What a missing or malformed {@code endpoint.json} says.
     *
     * <p>Plain {@code >} rather than the arrow the design document draws, because this line has to
     * survive a Windows console code page that has no {@code →}.
     */
    static final String NOT_RUNNING =
        "the korTTY control API is not running; enable it in Settings > Terminal > Control API";

    /** What {@code --current} says when no ancestor process is a korTTY local shell. */
    static final String NOT_IN_PANE =
        "not running inside a korTTY local shell pane (use --focused or --pane)";

    private KorttyCli() {
    }

    /**
     * Runs one invocation and returns its exit code.
     *
     * @param args the argument vector, exactly as the launcher received it
     * @param out where a successful result is printed, one compact line unless {@code --raw} or
     *     {@code --pretty} say otherwise
     * @param err where a failure is printed; stdout stays empty on every failure path
     * @return one of {@link #EXIT_OK}, {@link #EXIT_FAILED}, {@link #EXIT_SYNTAX},
     *     {@link #EXIT_UNREACHABLE} or {@link #EXIT_TIMEOUT}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        boolean quiet = CliArguments.looksQuiet(args);
        CliInvocation invocation;
        try {
            invocation = CliArguments.parse(args);
        } catch (CliSyntaxException e) {
            return syntaxError(err, quiet, e);
        }
        if (invocation.has(CliArguments.FLAG_VERSION)) {
            print(out, invocation.quiet(), versionLine());
            return EXIT_OK;
        }
        if (invocation.has(CliArguments.FLAG_HELP)) {
            print(out, invocation.quiet(), helpFor(invocation));
            return EXIT_OK;
        }
        CliCommands.Call call;
        CliInvocation resolved;
        try {
            resolved = readStdinPayload(invocation);
            call = CliCommands.toCall(resolved);
        } catch (CliSyntaxException e) {
            return syntaxError(err, invocation.quiet(), e);
        } catch (IOException e) {
            return fail(err, invocation.quiet(), "cannot read the payload from stdin: "
                + e.getMessage(), EXIT_SYNTAX);
        }
        return execute(resolved, call, out, err);
    }

    /**
     * The process entry point; the only {@code System.exit} caller in this package.
     *
     * <p>Both streams are pinned to UTF-8 rather than inherited with the platform encoding. stdout
     * carries JSON that a script pipes into a parser, and {@code pane read} puts a terminal's own
     * text in it, which is arbitrary Unicode — on a console the JVM reports as POSIX or cp1252 those
     * characters would be replaced by {@code ?} and the output would no longer be the pane's
     * contents. stderr follows so a message the server wrote survives the same way.
     */
    public static void main(String[] args) {
        System.exit(run(args, utf8(System.out), utf8(System.err)));
    }

    /** Re-encodes one inherited stream as UTF-8, preserving any {@code System.setOut} redirection. */
    private static PrintStream utf8(PrintStream stream) {
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    // --- the call ----------------------------------------------------------------------------

    private static int execute(CliInvocation invocation, CliCommands.Call call, PrintStream out,
                               PrintStream err) {
        boolean quiet = invocation.quiet();
        EndpointDescriptor endpoint;
        try {
            endpoint = ControlDiscovery.read(invocation.configDir());
        } catch (IOException e) {
            return fail(err, quiet, NOT_RUNNING, EXIT_UNREACHABLE);
        }
        try (ControlClient client = ControlClient.connect(endpoint, invocation.timeoutMillis())) {
            client.authenticate(CLIENT_NAME);
            if (invocation.has("current") && !resolveCurrent(client, call.params())) {
                return fail(err, quiet, NOT_IN_PANE, EXIT_FAILED);
            }
            if (call.stream()) {
                return streamEvents(invocation, call, client, out);
            }
            JsonElement result = client.call(call.method(), call.params());
            if (!quiet) {
                CliOutput.printResult(out, result, invocation.raw(), invocation.pretty());
            }
            return EXIT_OK;
        } catch (CliServerException e) {
            if (!quiet) {
                CliOutput.printError(err, e, invocation.pretty());
            }
            return e.exitCode();
        } catch (SocketTimeoutException e) {
            return fail(err, quiet, "the control API did not answer within "
                + invocation.timeoutMillis() + " ms (raise --timeout)", EXIT_TIMEOUT);
        } catch (IOException e) {
            return fail(err, quiet, "cannot reach the korTTY control API on "
                + endpoint.displayText() + "; korTTY may have exited. Restart it, or delete"
                + " its control directory and restart it", EXIT_UNREACHABLE);
        }
    }

    /**
     * Streams events until {@code --count}, the deadline, or the server closes the connection.
     *
     * <p>The {@code events.subscribe} result itself is swallowed on purpose: the documented output of
     * {@code events} is one event object per line, and a subscription acknowledgement on the same
     * stream would break every {@code while read -r line} loop reading it.
     */
    private static int streamEvents(CliInvocation invocation, CliCommands.Call call,
                                    ControlClient client, PrintStream out)
            throws IOException, CliServerException {
        client.call(call.method(), call.params());
        // Parsed the way CliArguments validated it — as a stripped long — and clamped, although the
        // parser has already bounded --count to the int range. A NumberFormatException here would
        // escape run() as a stack trace: no documented exit code, and output despite --quiet, and all
        // of it after events.subscribe is on the wire and korTTY has logged the subscription.
        int count = (int) Math.min(Long.parseLong(invocation.flag("count", "0").strip()),
            Integer.MAX_VALUE);
        long deadline = invocation.timeoutMillis() <= 0
            ? Long.MAX_VALUE : System.currentTimeMillis() + invocation.timeoutMillis();
        boolean quiet = invocation.quiet();
        boolean pretty = invocation.pretty();
        client.stream(frame -> {
            if (!quiet) {
                out.println(CliOutput.json(frame, pretty));
            }
        }, count, deadline);
        return EXIT_OK;
    }

    /**
     * Replaces the {@code --current} sentinel with the pane this process runs in.
     *
     * <p>It walks at most {@link ControlApiProtocol#MAX_RESOLVE_PIDS} ancestors and lets korTTY match
     * them against every pane's local shell pid. There is no fallback to the focused pane: silently
     * typing into whatever the user happens to be looking at is exactly how a script ends up driving
     * the wrong shell.
     *
     * @return false when no ancestor matched, which the caller reports as exit 1
     */
    private static boolean resolveCurrent(ControlClient client, JsonObject params)
            throws IOException, CliServerException {
        List<Long> pids = PaneAncestry.ancestorPids(ControlApiProtocol.MAX_RESOLVE_PIDS);
        JsonObject request = new JsonObject();
        JsonArray array = new JsonArray();
        pids.forEach(array::add);
        request.add("pids", array);
        JsonElement result = client.call("pane.resolve", request);
        if (result == null || !result.isJsonObject()) {
            return false;
        }
        JsonElement pane = result.getAsJsonObject().get("pane");
        if (pane == null || !pane.isJsonObject()) {
            return false;
        }
        JsonElement paneId = pane.getAsJsonObject().get("pane_id");
        if (paneId == null || !paneId.isJsonPrimitive()) {
            return false;
        }
        params.addProperty("pane", paneId.getAsString());
        return true;
    }

    // --- helpers -----------------------------------------------------------------------------

    /**
     * Folds a {@code --stdin} payload into the invocation.
     *
     * <p>Doing it here rather than inside {@link CliCommands#toCall} is what keeps the mapper a pure
     * function: by the time it runs, the text is an ordinary flag value.
     */
    private static CliInvocation readStdinPayload(CliInvocation invocation) throws IOException {
        if (!invocation.has(CliArguments.FLAG_STDIN)) {
            return invocation;
        }
        String payload = readAll(System.in);
        if ("raw".equals(invocation.group())) {
            return CliArguments.withOperand(invocation, payload.strip());
        }
        return CliArguments.withFlag(invocation, CliArguments.FLAG_TEXT, payload);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * The version banner.
     *
     * <p>The version comes from the jar manifest's {@code Implementation-Version} rather than from
     * {@code KorTTYApplication}, whose static initialiser this package must not trigger. On a raw
     * class path — a test JVM, a development run — there is no manifest and the banner says
     * {@code dev}, which is still a successful answer: {@code --version} is the per-OS release smoke
     * command and must work with no server running.
     */
    static String versionLine() {
        Package self = KorttyCli.class.getPackage();
        String version = self == null ? null : self.getImplementationVersion();
        return "korTTY control CLI " + (version == null || version.isBlank() ? "dev" : version)
            + " (protocol " + ControlApiProtocol.PROTOCOL_VERSION + ")";
    }

    private static String helpFor(CliInvocation invocation) {
        if (invocation.group() == null) {
            return CliUsage.top();
        }
        if (invocation.verb() == null) {
            return CliUsage.group(invocation.group());
        }
        return CliUsage.verb(invocation.group(), invocation.verb());
    }

    private static int syntaxError(PrintStream err, boolean quiet, CliSyntaxException e) {
        return fail(err, quiet, e.message(), EXIT_SYNTAX);
    }

    private static int fail(PrintStream err, boolean quiet, String message, int exitCode) {
        if (!quiet && err != null) {
            err.println(CliOutput.PROGRAM + ": " + message);
        }
        return exitCode;
    }

    private static void print(PrintStream out, boolean quiet, String text) {
        if (!quiet && out != null) {
            out.print(text.endsWith(System.lineSeparator()) ? text : text + System.lineSeparator());
        }
    }
}
