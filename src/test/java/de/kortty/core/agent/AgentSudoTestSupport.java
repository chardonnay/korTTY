package de.kortty.core.agent;

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
}
