package de.kortty.core.agent;

import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Gives the loopback SSH fixture a controlled sudo executable and a prompt to try replaying. */
public final class AgentSudoTestSupport {

    private AgentSudoTestSupport() {
    }

    public static String wrap(String command, AgentSudoAuthentication authentication, String sudoExecutable) {
        return AgentSudoCommand.wrap(command, authentication, sudoExecutable);
    }

    public static String promptMarker(AgentSudoAuthentication authentication) {
        return authentication.promptMarker();
    }

    public static AgentSudoPty openPty(ClientSession session, Supplier<String> workingDirectory,
                                      AgentSudoAuthentication authentication, BooleanSupplier cancelled,
                                      String sudoExecutable) throws Exception {
        return SshAgentSudoPty.open(session, workingDirectory, authentication, cancelled, sudoExecutable);
    }

    public static void writeArmedMarker(AgentSudoAuthentication authentication, OutputStream output) throws IOException {
        output.write(authentication.armedMarker().getBytes(StandardCharsets.US_ASCII));
    }

    public static void writeReadyMarker(AgentSudoAuthentication authentication, OutputStream output) throws IOException {
        output.write(authentication.readyMarker().getBytes(StandardCharsets.US_ASCII));
    }
}
