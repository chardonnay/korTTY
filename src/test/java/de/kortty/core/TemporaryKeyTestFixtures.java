package de.kortty.core;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared fixtures for the temporary-SSH-key tests: key generation in the formats users paste, a
 * loopback SSHD server that accepts exactly one public key, and a view of the temp folder.
 */
public final class TemporaryKeyTestFixtures {

    private TemporaryKeyTestFixtures() {
    }

    public static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Generated through MINA's EdDSA support (BouncyCastle), as the OpenSSH writer requires. */
    public static KeyPair ed25519KeyPair() throws Exception {
        return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    }

    /** An unencrypted PKCS#8 PEM ({@code BEGIN PRIVATE KEY}). */
    public static String pkcs8Pem(KeyPair keyPair) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(new JcaPKCS8Generator(keyPair.getPrivate(), null));
        }
        return out.toString();
    }

    /** An OpenSSL "traditional" PEM, for RSA {@code BEGIN RSA PRIVATE KEY}. */
    public static String traditionalPem(KeyPair keyPair) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(keyPair.getPrivate());
        }
        return out.toString();
    }

    /** An unencrypted OpenSSH private key ({@code BEGIN OPENSSH PRIVATE KEY}). */
    public static String openSshPrivateKey(KeyPair keyPair) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "kortty-test", null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Names of the entries in {@code java.io.tmpdir} that start with {@code prefix}. */
    public static Set<String> tempFolderEntries(String prefix) throws IOException {
        Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tempDirectory, prefix + "*")) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        }
        return names;
    }

    /**
     * A loopback server, not yet started, whose only authentication method is the public key
     * {@code accepted}: no password and no keyboard-interactive login, so a connection succeeds
     * only when the client offers exactly that key.
     */
    public static SshServer publicKeyOnlyServer(Path hostKeyFile, PublicKey accepted) {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        server.setPasswordAuthenticator(null);
        server.setKeyboardInteractiveAuthenticator(null);
        server.setPublickeyAuthenticator((username, key, session) -> KeyUtils.compareKeys(key, accepted));
        return server;
    }

    /** Accepts every first-use host key without a prompt. */
    public static SshHostKeyTrustManager acceptingTrustManager(Path store) {
        return new SshHostKeyTrustManager(store, new SshHostKeyTrustManager.HostKeyPrompt() {
            @Override
            public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
                return true;
            }

            @Override
            public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            }

            @Override
            public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
            }
        });
    }

    /** A shell that prints a prompt and echoes its input until the channel closes. */
    public static final class EchoShell implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;
        private Thread worker;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            worker = new Thread(() -> {
                try {
                    out.write("ready$ ".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "temporary-key-test-shell");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
