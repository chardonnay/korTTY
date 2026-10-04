package de.kortty.ui;

import de.kortty.model.ServerConnection;
import org.apache.sshd.client.session.ClientSession;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

/**
 * {@link PaneSessionSupplier}: the borrowed session is handed out only while the pane still runs
 * the user and host it ran when SFTP attached; a re-pointed, closed or vanished pane gives null.
 */
class PaneSessionSupplierTest {

    private final Object view = new Object();
    private final Object pane = new Object();

    @Test
    void theSameIdentityGivesTheCurrentSession() {
        ClientSession session = fakeSession(true);
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(
            paneSession(connection("id-1", "alice", "srv.example"), session));
        PaneSessionSupplier<Object, Object> supplier = supplier(connection("id-1", "alice", "srv.example"), now);

        assertThat(supplier.get()).isSameInstanceAs(session);

        ClientSession reconnected = fakeSession(true);
        now.set(paneSession(connection("id-1", "alice", "srv.example"), reconnected));
        assertThat(supplier.get()).isSameInstanceAs(reconnected);
    }

    @Test
    void aPaneRePointedToAnotherUserGivesNull() {
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(
            paneSession(connection("id-1", "alice", "srv.example"), fakeSession(true)));
        PaneSessionSupplier<Object, Object> supplier = supplier(connection("id-1", "alice", "srv.example"), now);

        now.set(paneSession(connection("id-1", "root", "srv.example"), fakeSession(true)));

        assertThat(supplier.get()).isNull();
    }

    @Test
    void aPaneRePointedToAnotherHostOrConnectionGivesNull() {
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>();
        PaneSessionSupplier<Object, Object> supplier = supplier(connection("id-1", "alice", "srv.example"), now);

        now.set(paneSession(connection("id-1", "alice", "other.example"), fakeSession(true)));
        assertThat(supplier.get()).isNull();

        now.set(paneSession(connection("id-2", "alice", "srv.example"), fakeSession(true)));
        assertThat(supplier.get()).isNull();
    }

    @Test
    void theHostIsComparedWithoutCase() {
        ClientSession session = fakeSession(true);
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(
            paneSession(connection("id-1", "alice", "SRV.Example"), session));
        PaneSessionSupplier<Object, Object> supplier = supplier(connection("id-1", "alice", "srv.example"), now);

        assertThat(supplier.get()).isSameInstanceAs(session);
    }

    @Test
    void aConnectorRunningAnotherUserGivesNullEvenWithTheSameOrigin() {
        ServerConnection origin = connection("id-1", "alice", "srv.example");
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(
            new PaneSessionSupplier.PaneSession(PaneSessionSupplier.Identity.of(origin),
                PaneSessionSupplier.Identity.of(connection(null, "root", "srv.example")), fakeSession(true)));

        assertThat(supplier(origin, now).get()).isNull();
    }

    @Test
    void aClosedSessionOrAGonePaneGivesNull() {
        ServerConnection origin = connection("id-1", "alice", "srv.example");
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(
            paneSession(origin, fakeSession(false)));
        PaneSessionSupplier<Object, Object> supplier = supplier(origin, now);
        assertThat(supplier.get()).isNull();

        now.set(paneSession(origin, null));
        assertThat(supplier.get()).isNull();

        now.set(null);
        assertThat(supplier.get()).isNull();
    }

    @Test
    void aThrowingResolverGivesNull() {
        PaneSessionSupplier<Object, Object> supplier = new PaneSessionSupplier<>(view, pane,
            PaneSessionSupplier.Identity.of(connection("id-1", "alice", "srv.example")),
            (v, p) -> {
                throw new IllegalStateException("pane disposed");
            });

        assertThat(supplier.get()).isNull();
    }

    @Test
    void theIdentityNamesNoUser() {
        String text = PaneSessionSupplier.Identity.of(connection("id-1", "alice", "srv.example")).toString();
        assertThat(text).doesNotContain("alice");
    }

    @Test
    void theTerminalViewResolverDoesNotHoldTheView() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java")).replace("\r\n", "\n");
        assertThat(source).contains("TerminalView::currentPaneSession");
        assertThat(source).contains("private static PaneSessionSupplier.@Nullable PaneSession currentPaneSession(");
    }

    private PaneSessionSupplier<Object, Object> supplier(ServerConnection attached,
            AtomicReference<PaneSessionSupplier.PaneSession> now) {
        return new PaneSessionSupplier<>(view, pane, PaneSessionSupplier.Identity.of(attached),
            (v, p) -> now.get());
    }

    private static PaneSessionSupplier.PaneSession paneSession(ServerConnection origin, ClientSession session) {
        PaneSessionSupplier.Identity identity = PaneSessionSupplier.Identity.of(origin);
        return new PaneSessionSupplier.PaneSession(identity, identity, session);
    }

    private static ServerConnection connection(String id, String user, String host) {
        ServerConnection connection = new ServerConnection("c", host, 22, user);
        connection.setId(id);
        return connection;
    }

    private static ClientSession fakeSession(boolean open) {
        AtomicBoolean isOpen = new AtomicBoolean(open);
        return (ClientSession) Proxy.newProxyInstance(PaneSessionSupplierTest.class.getClassLoader(),
            new Class<?>[] {ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isOpen" -> isOpen.get();
                case "isClosing", "isClosed" -> !isOpen.get();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "FakeSession";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }
}
