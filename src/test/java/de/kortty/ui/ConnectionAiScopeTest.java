package de.kortty.ui;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class ConnectionAiScopeTest {

    @Test
    void noConnectionPinsNoSkills() {
        assertThat(ConnectionAiScope.assignedSkillIds(null)).isNull();
    }

    @Test
    void aConnectionWithoutAssignedSkillsPinsNone() {
        ServerConnection connection = new ServerConnection();
        connection.setAiSkillIds(List.of());

        assertThat(ConnectionAiScope.assignedSkillIds(connection)).isNull();
    }

    @Test
    void theAssignedSkillsAreTheConnectionsOwn() {
        ServerConnection connection = new ServerConnection();
        connection.setAiSkillIds(List.of("perl-rules", "k8s-rules"));

        assertThat(ConnectionAiScope.assignedSkillIds(connection)).containsExactly("perl-rules", "k8s-rules").inOrder();
    }
}
