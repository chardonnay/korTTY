package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import static com.google.common.truth.Truth.assertThat;


class Mosh4jTtyConnectorTest {

    @Test
    void isConnectedStaysTrueDuringTransientInterruption() throws Exception {
        Mosh4jTtyConnector connector = new Mosh4jTtyConnector(
                new ServerConnection("Test", "example.com", 22, "daniel"),
                "secret");

        setField(connector, "connected", new java.util.concurrent.atomic.AtomicBoolean(true));
        setField(connector, "interruptionStartedAtMs", 1L);
        setField(connector, "frontend", new TestFrontend(false));
        setField(connector, "frontendIsRunning", TestFrontend.class.getDeclaredMethod("isRunning"));

        assertThat(connector.isConnected()).isTrue();
    }

    @Test
    void isConnectedIsFalseAfterConnectorClosed() {
        Mosh4jTtyConnector connector = new Mosh4jTtyConnector(
                new ServerConnection("Test", "example.com", 22, "daniel"),
                "secret");

        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void reusesBouncyCastleFromParentClassLoader() {
        assertThat(Mosh4jTtyConnector.parentProvidesBouncyCastle(getClass().getClassLoader())).isTrue();
        assertThat(Mosh4jTtyConnector.parentProvidesBouncyCastle(new ClassLoader(null) {})).isFalse();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestFrontend {
        private final boolean running;

        private TestFrontend(boolean running) {
            this.running = running;
        }

        @SuppressWarnings("unused")
        public boolean isRunning() {
            return running;
        }
    }
}
