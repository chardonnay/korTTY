package de.kortty.core;

import de.kortty.core.OpenSshKnownHostsParser.KnownHostsEntry;
import de.kortty.core.OpenSshKnownHostsParser.KnownHostsFile;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class OpenSshKnownHostsParserTest {

    static String fixture() throws IOException {
        try (InputStream in = OpenSshKnownHostsParserTest.class.getResourceAsStream("/known-hosts/known_hosts_fixture")) {
            assertThat(in).isNotNull();
            // Normalised to LF, so a CRLF checkout does not change what the tests see.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Test
    void parsesPlainBracketedAndListedHostsWithTheirPorts() throws Exception {
        KnownHostsFile file = OpenSshKnownHostsParser.parse(fixture());

        assertThat(endpoints(file)).containsExactly(
            "plain.example.com:22 ssh-ed25519",
            "web.example.com:22 ecdsa-sha2-nistp256",
            "192.0.2.10:22 ecdsa-sha2-nistp256",
            "alt.example.com:2222 ssh-rsa",
            "2001:db8::1:2200 ecdsa-sha2-nistp384",
            "port22.example.com:22 ssh-ed25519",
            "multi.example.com:22 ssh-rsa",
            "multi.example.com:22 ssh-ed25519",
            "multi.example.com:22 ecdsa-sha2-nistp256",
            "duplicate.example.com:22 ssh-ed25519",
            "duplicate.example.com:22 ssh-ed25519").inOrder();
        KnownHostsEntry web = file.entries().get(1);
        KnownHostsEntry ip = file.entries().get(2);
        assertThat(web.lineNumber()).isEqualTo(5);
        assertThat(ip.fingerprintSha256()).isEqualTo(web.fingerprintSha256());
        assertThat(web.fingerprintSha256()).startsWith("SHA256:");
        assertThat(web.publicKeyLine()).startsWith("ecdsa-sha2-nistp256 AAAA");
        assertThat(web.publicKeyLine()).doesNotContain("web server");
    }

    @Test
    void countsEverySkippedKindAndReportsMalformedLineNumbers() throws Exception {
        KnownHostsFile file = OpenSshKnownHostsParser.parse(fixture());

        assertThat(file.hashed()).isEqualTo(2);
        assertThat(file.revoked()).isEqualTo(1);
        assertThat(file.certAuthority()).isEqualTo(1);
        assertThat(file.patterns()).isEqualTo(1);
        assertThat(file.unsupportedKeyType()).isEqualTo(1);
        // broken base64, missing key, port 99999, rsa label on an ed25519 blob, unknown marker
        assertThat(file.malformedLines()).containsExactly(18, 19, 20, 21, 22).inOrder();
        assertThat(file.revokedFingerprints()).hasSize(1);
        assertThat(file.revokedFingerprints()).contains(file.entries().get(5).fingerprintSha256());
    }

    @Test
    void crlfLineEndingsAndByteOrderMarkParseTheSame() throws Exception {
        String lf = fixture();
        KnownHostsFile expected = OpenSshKnownHostsParser.parse(lf);
        KnownHostsFile crlf = OpenSshKnownHostsParser.parse("﻿" + lf.replace("\n", "\r\n"));

        assertThat(crlf).isEqualTo(expected);
    }

    @Test
    void mixedPatternListImportsOnlyThePlainNames() throws Exception {
        String key = fixture().lines().filter(line -> line.startsWith("plain.example.com ")).findFirst().orElseThrow()
            .split(" ", 2)[1];
        KnownHostsFile file = OpenSshKnownHostsParser.parse("good.example.com,*.example.org,!bad.example.com " + key);

        assertThat(endpoints(file)).containsExactly("good.example.com:22 ssh-ed25519");
        assertThat(file.patterns()).isEqualTo(2);
        assertThat(file.malformedLines()).isEmpty();
    }

    @Test
    void rejectsHostElementsWithInvalidCharactersOrPorts() throws Exception {
        String key = fixture().lines().filter(line -> line.startsWith("plain.example.com ")).findFirst().orElseThrow()
            .split(" ", 2)[1];
        String content = String.join("\n",
            "ok.example.com,bad/host " + key,
            "[x.example.com]:+22 " + key,
            "[x.example.com]:0 " + key,
            "[x.example.com] " + key,
            "[]:22 " + key,
            "a,,b " + key);

        KnownHostsFile file = OpenSshKnownHostsParser.parse(content);

        assertThat(endpoints(file)).containsExactly("x.example.com:22 ssh-ed25519");
        assertThat(file.malformedLines()).containsExactly(1, 2, 3, 5, 6).inOrder();
    }

    @Test
    void emptyAndCommentOnlyContentHasNoEntries() {
        KnownHostsFile file = OpenSshKnownHostsParser.parse("# nothing\n\n   \n");

        assertThat(file.entries()).isEmpty();
        assertThat(file.malformedLines()).isEmpty();
        assertThat(OpenSshKnownHostsParser.parse(null).entries()).isEmpty();
    }

    @Test
    void readRefusesDirectoriesAndOversizedFiles() throws Exception {
        Path dir = Files.createTempDirectory("kortty-known-hosts-parser-");
        expectThrows(IOException.class, () -> OpenSshKnownHostsParser.read(dir));

        Path big = dir.resolve("known_hosts");
        try (var channel = java.nio.channels.FileChannel.open(big,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(OpenSshKnownHostsParser.MAX_FILE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {'\n'}));
        }
        expectThrows(IOException.class, () -> OpenSshKnownHostsParser.read(big));

        Path fixtureFile = dir.resolve("fixture");
        Files.writeString(fixtureFile, fixture());
        assertThat(OpenSshKnownHostsParser.read(fixtureFile)).isEqualTo(OpenSshKnownHostsParser.parse(fixture()));
    }

    @Test
    void preferenceFollowsTheClientsHostKeyAlgorithmOrder() {
        int ecdsa = OpenSshKnownHostsParser.preferenceRank("ecdsa-sha2-nistp256");
        int ed25519 = OpenSshKnownHostsParser.preferenceRank("ssh-ed25519");
        int rsa = OpenSshKnownHostsParser.preferenceRank("ssh-rsa");

        assertThat(ecdsa).isLessThan(ed25519);
        assertThat(ed25519).isLessThan(rsa);
        assertThat(rsa).isLessThan(Integer.MAX_VALUE);
        assertThat(OpenSshKnownHostsParser.preferenceRank("ssh-dss")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void defaultFileIsTheUsersOpenSshKnownHosts() {
        Path path = OpenSshKnownHostsParser.defaultKnownHostsFile();

        assertThat(path.getFileName().toString()).isEqualTo("known_hosts");
        assertThat(path.getParent().getFileName().toString()).isEqualTo(".ssh");
    }

    private static List<String> endpoints(KnownHostsFile file) {
        return file.entries().stream().map(entry -> entry.host() + ":" + entry.port() + " " + entry.keyType()).toList();
    }
}
