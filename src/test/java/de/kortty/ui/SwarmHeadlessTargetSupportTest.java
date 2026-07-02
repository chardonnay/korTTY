package de.kortty.ui;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class SwarmHeadlessTargetSupportTest {

    @Test
    void filtersLocalShellNullAndIdLessConnections() {
        ServerConnection ssh = new ServerConnection("a", "hostA", 22, "root");
        ServerConnection local = new ServerConnection("l", "localhost", 0, "me");
        local.setProtocol(ConnectionProtocol.LOCAL_SHELL);

        List<ServerConnection> schedulable =
            SwarmHeadlessTargetSupport.schedulableConnections(Arrays.asList(ssh, local, null));

        assertThat(schedulable).containsExactly(ssh);
        assertThat(SwarmHeadlessTargetSupport.schedulableConnections(null)).isEmpty();
    }

    @Test
    void agentIdsAreStablePerConnectionAndDistinctAcrossConnections() {
        Map<String, String> cache = new HashMap<>();
        String first = SwarmHeadlessTargetSupport.stableAgentId(cache, "conn-1");
        String again = SwarmHeadlessTargetSupport.stableAgentId(cache, "conn-1");
        String other = SwarmHeadlessTargetSupport.stableAgentId(cache, "conn-2");

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(first).startsWith("swarm-headless-");
    }
}
