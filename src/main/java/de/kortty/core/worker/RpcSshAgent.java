package de.kortty.core.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentKeyConstraint;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionContext;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The worker's SSH agent: its identities and signatures come from korTTY over the control channel,
 * so the worker authenticates with a key without ever holding the private key. korTTY loads the key
 * as it does for a direct session and signs exactly the data MINA hands the agent — the session
 * identifier and the authentication request — so a compromised worker can at most obtain signatures
 * for the logins it is already making.
 */
final class RpcSshAgent implements SshAgent {

    /** How long korTTY may take to answer (it may first ask for a passphrase). */
    static final long TIMEOUT_MILLIS = 5 * 60_000L;

    private final WorkerEndpoint endpoint;
    /** {@code target} or {@code jump}: whose keys. */
    private final String role;
    private volatile boolean open = true;

    RpcSshAgent(WorkerEndpoint endpoint, String role) {
        this.endpoint = endpoint;
        this.role = role;
    }

    /** An agent factory for a client whose sessions authenticate with {@code role}'s keys. */
    static SshAgentFactory factory(WorkerEndpoint endpoint, String role) {
        return new SshAgentFactory() {
            @Override
            public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
                return List.of();
            }

            @Override
            public SshAgent createClient(Session session, FactoryManager manager) {
                return new RpcSshAgent(endpoint, role);
            }

            @Override
            public SshAgentServer createServer(ConnectionService service) throws IOException {
                throw new IOException("agent forwarding is not offered");
            }
        };
    }

    @Override
    public Iterable<? extends Map.Entry<PublicKey, String>> getIdentities() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("role", role);
        JsonObject result = endpoint.call("agent.identities", params, TIMEOUT_MILLIS);
        List<Map.Entry<PublicKey, String>> identities = new ArrayList<>();
        JsonArray keys = result.has("keys") ? result.getAsJsonArray("keys") : new JsonArray();
        for (JsonElement key : keys) {
            identities.add(new AbstractMap.SimpleImmutableEntry<>(WorkerKeys.parse(key.getAsString()), "kortty"));
        }
        return identities;
    }

    @Override
    public Map.Entry<String, byte[]> sign(SessionContext session, PublicKey key, String algorithm, byte[] data)
            throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("role", role);
        params.addProperty("key", PublicKeyEntry.toString(key));
        params.addProperty("algorithm", algorithm);
        params.addProperty("data", Base64.getEncoder().encodeToString(data));
        JsonObject result = endpoint.call("agent.sign", params, TIMEOUT_MILLIS);
        return new AbstractMap.SimpleImmutableEntry<>(result.get("algorithm").getAsString(),
            Base64.getDecoder().decode(result.get("signature").getAsString()));
    }

    @Override
    public KeyPair resolveLocalIdentity(PublicKey key) {
        return null;
    }

    @Override
    public void addIdentity(KeyPair key, String comment, SshAgentKeyConstraint... constraints) throws IOException {
        throw new IOException("read-only agent");
    }

    @Override
    public void removeIdentity(PublicKey key) throws IOException {
        throw new IOException("read-only agent");
    }

    @Override
    public void removeAllIdentities() throws IOException {
        throw new IOException("read-only agent");
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }
}
