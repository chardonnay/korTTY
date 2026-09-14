package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The connect probe that decides whether a leftover socket may be unlinked. */
class StaleSocketProbeTest {

    private Path root;

    private ServerSocketChannel listener;

    @BeforeMethod
    void createRoot() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
    }

    @AfterMethod(alwaysRun = true)
    void cleanUp() throws IOException {
        if (listener != null) {
            listener.close();
            listener = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    @Test(timeOut = 30_000)
    void anEmptyDirectoryHasNothingToClassify() {
        assertThat(StaleSocketProbe.classify(root.resolve("control.sock")))
            .isEqualTo(StaleSocketProbe.Verdict.NONE);
        assertThat(StaleSocketProbe.classify(null)).isEqualTo(StaleSocketProbe.Verdict.NONE);
    }

    @Test(timeOut = 30_000)
    void aSocketWhoseListenerIsGoneIsStale() throws IOException {
        Path socket = bind();
        listener.close();
        listener = null;

        assertThat(Files.exists(socket)).isTrue();
        assertThat(StaleSocketProbe.classify(socket)).isEqualTo(StaleSocketProbe.Verdict.STALE);
    }

    @Test(timeOut = 30_000)
    void aSocketWithALiveListenerIsLive() throws IOException {
        Path socket = bind();

        assertThat(StaleSocketProbe.classify(socket)).isEqualTo(StaleSocketProbe.Verdict.LIVE);
    }

    @Test(timeOut = 30_000)
    void aRegularFileIsForeign() throws IOException {
        Path file = Files.createFile(root.resolve("control.sock"));

        assertThat(StaleSocketProbe.classify(file)).isEqualTo(StaleSocketProbe.Verdict.FOREIGN);
    }

    @Test(timeOut = 30_000)
    void aSymlinkIsForeign() throws IOException {
        Path target = Files.createFile(root.resolve("target"));
        Path link = root.resolve("control.sock");
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            throw new SkipException("this platform does not allow creating symbolic links");
        }

        assertThat(StaleSocketProbe.classify(link)).isEqualTo(StaleSocketProbe.Verdict.FOREIGN);
    }

    @Test(timeOut = 30_000)
    void aDirectoryIsForeign() throws IOException {
        Path directory = Files.createDirectory(root.resolve("control.sock"));

        assertThat(StaleSocketProbe.classify(directory)).isEqualTo(StaleSocketProbe.Verdict.FOREIGN);
    }

    private Path bind() throws IOException {
        Path socket = root.resolve("control.sock");
        UdsTestSupport.requireBindableSocketPath(root);
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listener.bind(UnixDomainSocketAddress.of(socket));
        return socket;
    }
}
