package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The suite shares one JVM, so a control-API thread that outlives {@code close()} would leak into
 * every later test.
 */
class ControlApiThreadHygieneTest {

    private static final long GRACE_MILLIS = 2_000L;

    private Path root;

    private ControlApiServer server;

    @BeforeMethod
    void createRoot() throws IOException {
        UdsTestSupport.skipOnWindows();
        root = UdsTestSupport.newTempRoot();
        UdsTestSupport.requireBindableSocketPath(root.resolve(ControlDirectory.DIRECTORY_NAME));
    }

    @AfterMethod(alwaysRun = true)
    void cleanUp() {
        if (server != null) {
            server.close();
            server = null;
        }
        UdsTestSupport.deleteTree(root);
    }

    @Test(timeOut = 30_000)
    void noControlThreadSurvivesClose() throws Exception {
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(),
            ControlApiServerLifecycleTest.helloRegistry(() -> true), () -> true,
            System::currentTimeMillis, "3.4.1", "hygiene-instance");
        server.applyEnabledState();
        EndpointDescriptor endpoint = server.endpoint().orElseThrow();
        try (UdsTestSupport.Client one = new UdsTestSupport.Client(endpoint);
                UdsTestSupport.Client two = new UdsTestSupport.Client(endpoint)) {
            one.authenticate(endpoint.token());
            two.authenticate(endpoint.token());
            assertThat(controlThreadNames()).isNotEmpty();
        }

        server.close();
        server = null;

        long deadline = System.nanoTime() + GRACE_MILLIS * 1_000_000L;
        Set<String> surviving = controlThreadNames();
        while (!surviving.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
            surviving = controlThreadNames();
        }

        assertThat(surviving).isEmpty();
    }

    @Test(timeOut = 30_000)
    void everyControlThreadIsADaemon() throws Exception {
        server = new ControlApiServer(root, UdsTestSupport.posixProbe(),
            ControlApiServerLifecycleTest.helloRegistry(() -> true), () -> true,
            System::currentTimeMillis, "3.4.1", "hygiene-instance");
        server.applyEnabledState();

        List<Thread> control = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith(ControlThreads.NAME_PREFIX))
            .toList();

        assertThat(control).isNotEmpty();
        for (Thread thread : control) {
            assertThat(thread.isDaemon()).isTrue();
        }
    }

    private static Set<String> controlThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .map(Thread::getName)
            .filter(name -> name.startsWith(ControlThreads.NAME_PREFIX))
            .collect(Collectors.toSet());
    }
}
