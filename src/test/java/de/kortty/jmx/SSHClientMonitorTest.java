package de.kortty.jmx;

import de.kortty.core.ActiveConnectionRegistry;
import org.testng.annotations.Test;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SSHClientMonitorTest {

    @Test
    void connectionAttributesComeLiveFromTheRegistry() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry();
        SSHClientMonitor monitor = new SSHClientMonitor(registry);
        assertThat(monitor.getActiveConnectionCount()).isEqualTo(0);
        assertThat(monitor.getActiveConnectionNames()).isEmpty();
        assertThat(monitor.getConnectionStatistics()).isEmpty();

        Object primary = new Object();
        Object split = new Object();
        registry.connected(primary, "prod-web", ActiveConnectionRegistry.PROTOCOL_SSH);
        registry.connected(split, "prod-web", ActiveConnectionRegistry.PROTOCOL_SSH);
        registry.connected(new Object(), "laptop", ActiveConnectionRegistry.PROTOCOL_MOSH);

        // Each split pane on its own connector is a connection of its own.
        assertThat(monitor.getActiveConnectionCount()).isEqualTo(3);
        assertThat(monitor.getActiveConnectionNames())
            .containsExactly("prod-web", "prod-web", "laptop").inOrder();
        Map<String, String> statistics = monitor.getConnectionStatistics();
        assertThat(statistics).hasSize(3);
        assertThat(statistics.values().iterator().next())
            .startsWith("Connection: prod-web, Protocol: SSH, Connected At: ");

        registry.disconnected(split);
        assertThat(monitor.getActiveConnectionCount()).isEqualTo(2);
        assertThat(monitor.getActiveConnectionNames()).containsExactly("prod-web", "laptop").inOrder();
        assertThat(monitor.getConnectionStatistics()).hasSize(2);
    }

    @Test
    @SuppressWarnings("deprecation")
    void bufferedTextSizeIsNotTrackedAndAlwaysZero() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry();
        registry.connected(new Object(), "prod-web", ActiveConnectionRegistry.PROTOCOL_SSH);

        assertThat(new SSHClientMonitor(registry).getBufferedTextSize()).isEqualTo(0L);
    }

    @Test
    void requiresARegistry() {
        assertThrows(NullPointerException.class, () -> new SSHClientMonitor(null));
    }

    @Test
    void isAStandardMBeanWhoseAttributesReadFromTheRegistry() throws Exception {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry();
        registry.connected(new Object(), "prod-web", ActiveConnectionRegistry.PROTOCOL_SSH);
        registry.connected(new Object(), "laptop", ActiveConnectionRegistry.PROTOCOL_MOSH_CLIENT);

        // A private server (newMBeanServer is not kept in the factory's list), so the platform
        // server a running application registers into is never touched.
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        ObjectName name = new ObjectName("de.kortty:type=SSHClient");
        server.registerMBean(new SSHClientMonitor(registry), name);
        try {
            List<String> attributes = Arrays.stream(server.getMBeanInfo(name).getAttributes())
                .map(MBeanAttributeInfo::getName)
                .toList();
            assertThat(attributes).containsAtLeast(
                "ActiveConnectionCount", "ActiveConnectionNames", "ConnectionStatistics",
                "BufferedTextSize", "UsedMemoryBytes", "MaxMemoryBytes", "UptimeSeconds", "Version");

            assertThat(server.getAttribute(name, "ActiveConnectionCount")).isEqualTo(2);
            assertThat(server.getAttribute(name, "ActiveConnectionNames"))
                .isEqualTo(List.of("prod-web", "laptop"));
            assertThat((Map<?, ?>) server.getAttribute(name, "ConnectionStatistics")).hasSize(2);
            assertThat(server.getAttribute(name, "BufferedTextSize")).isEqualTo(0L);
        } finally {
            server.unregisterMBean(name);
        }
    }
}
