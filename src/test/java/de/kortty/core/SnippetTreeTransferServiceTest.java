package de.kortty.core;

import de.kortty.model.Snippet;
import de.kortty.model.SnippetFolder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetTreeTransferServiceTest {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    Path tempDir;
    SnippetFolderLayout layout;
    SshServer server;
    SshClient client;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-transfer-test");
        SnippetManager manager = new SnippetManager(tempDir.resolve("config"));
        SnippetFolder tools = manager.addFolder("tools", null);
        SnippetFolder lib = manager.addFolder("lib", tools.getId());
        add(manager, "run", "#!/bin/bash\r\necho run\r\n", "bash", tools.getId());
        add(manager, "helpers", "def x():\n    return 1\n", "python", lib.getId()).setExecutable(Boolean.FALSE);
        add(manager, "notes.md", "﻿hello", "plain", tools.getId());
        layout = SnippetFolderLayout.ofFolder(manager, tools.getId(), true);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop(true);
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static Snippet add(SnippetManager manager, String name, String content, String language, String folderId) {
        Snippet snippet = new Snippet(name, content, language);
        manager.addSnippet(snippet);
        snippet.setFolderId(folderId);
        return snippet;
    }

    private void assertTreeWritten(Path root) throws IOException {
        assertThat(Files.readString(root.resolve("tools/run.sh"))).isEqualTo("#!/bin/bash\necho run\n");
        assertThat(Files.readString(root.resolve("tools/notes.md"))).isEqualTo("hello");
        assertThat(Files.readString(root.resolve("tools/lib/helpers.py"))).isEqualTo("def x():\n    return 1\n");
        if (POSIX) {
            assertThat(mode(root.resolve("tools/run.sh"))).isEqualTo("rwxr-xr-x");
            assertThat(mode(root.resolve("tools/lib/helpers.py"))).isEqualTo("rw-r--r--");
            assertThat(mode(root.resolve("tools/notes.md"))).isEqualTo("rw-r--r--");
            assertThat(mode(root.resolve("tools/lib"))).isEqualTo("rwxr-xr-x");
        }
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    void localCopyWritesTheTreeWithModes() throws IOException {
        Path target = Files.createDirectory(tempDir.resolve("local"));
        List<String> progress = new ArrayList<>();
        SnippetTreeTransferService.Result result = SnippetTreeTransferService.writeLocal(target, layout,
            SnippetTreeTransferService.ConflictPolicy.OVERWRITE, (done, total, path) -> progress.add(done + "/" + total),
            () -> false);
        assertThat(result.written()).hasSize(3);
        assertThat(result.cancelled()).isFalse();
        assertThat(progress).contains("3/3");
        assertTreeWritten(target);
    }

    @Test
    void localCopyCanSkipExistingFiles() throws IOException {
        Path target = Files.createDirectory(tempDir.resolve("local"));
        Files.createDirectories(target.resolve("tools"));
        Files.writeString(target.resolve("tools/run.sh"), "mine");
        assertThat(SnippetTreeTransferService.existingLocal(target, layout)).containsExactly("tools/run.sh");
        SnippetTreeTransferService.Result result = SnippetTreeTransferService.writeLocal(target, layout,
            SnippetTreeTransferService.ConflictPolicy.SKIP, null, () -> false);
        assertThat(result.skipped()).containsExactly("tools/run.sh");
        assertThat(Files.readString(target.resolve("tools/run.sh"))).isEqualTo("mine");
    }

    @Test
    void cancellingStopsBeforeTheNextFile() throws IOException {
        Path target = Files.createDirectory(tempDir.resolve("local"));
        int[] calls = {0};
        SnippetTreeTransferService.Result result = SnippetTreeTransferService.writeLocal(target, layout,
            SnippetTreeTransferService.ConflictPolicy.OVERWRITE, (done, total, path) -> calls[0]++,
            () -> calls[0] >= 1);
        assertThat(result.cancelled()).isTrue();
        assertThat(result.written()).hasSize(1);
    }

    @Test
    void sftpUploadWritesTheTreeWithModes() throws Exception {
        Path remoteRoot = Files.createDirectory(tempDir.resolve("remote"));
        Files.createDirectories(remoteRoot.resolve("home/work"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("host.ser")));
        server.setPasswordAuthenticator((user, password, session) -> true);
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        server.start();

        client = SshClient.setUpDefaultClient();
        client.start();
        try (ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10)).getSession()) {
            session.addPasswordIdentity("secret");
            session.auth().verify(Duration.ofSeconds(10));
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                String target = RemotePathSupport.resolveTargetDirectory("/home/work", RemotePathSupport.sftpStartDirectory(sftp));
                assertThat(SnippetTreeTransferService.existingRemote(sftp, target, layout)).isEmpty();
                SnippetTreeTransferService.Result result = SnippetTreeTransferService.uploadRemote(sftp, target, layout,
                    SnippetTreeTransferService.ConflictPolicy.OVERWRITE, null, () -> false);
                assertThat(result.written()).hasSize(3);
                assertThat(SnippetTreeTransferService.existingRemote(sftp, target, layout)).hasSize(3);
            }
        }
        assertTreeWritten(remoteRoot.resolve("home/work"));
    }
}
