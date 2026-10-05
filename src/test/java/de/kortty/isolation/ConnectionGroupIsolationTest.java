package de.kortty.isolation;

import static com.google.common.truth.Truth.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.testng.annotations.Test;

/** Folder isolation levels follow their folder through a rename and go with it when it is deleted. */
class ConnectionGroupIsolationTest {

    private static Map<String, IsolationLevel> levels(Object... pairs) {
        Map<String, IsolationLevel> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (IsolationLevel) pairs[i + 1]);
        }
        return map;
    }

    @Test
    void renamingMovesTheFolderAndEverythingBelowIt() {
        Map<String, IsolationLevel> after = ConnectionGroupIsolation.renamed(
            levels("Lab", IsolationLevel.SANDBOX, "Lab/DB", IsolationLevel.PROCESS, "Lab2", IsolationLevel.NONE),
            "Lab", "Test");
        assertThat(after).containsExactly(
            "Lab2", IsolationLevel.NONE, "Test", IsolationLevel.SANDBOX, "Test/DB", IsolationLevel.PROCESS);
    }

    @Test
    void aMergeKeepsTheStricterLevel() {
        Map<String, IsolationLevel> after = ConnectionGroupIsolation.renamed(
            levels("Lab", IsolationLevel.PROCESS, "Test", IsolationLevel.SANDBOX), "Lab", "Test");
        assertThat(after).containsExactly("Test", IsolationLevel.SANDBOX);
        Map<String, IsolationLevel> other = ConnectionGroupIsolation.renamed(
            levels("Lab", IsolationLevel.SANDBOX, "Test", IsolationLevel.NONE), "Lab", "Test");
        assertThat(other).containsExactly("Test", IsolationLevel.SANDBOX);
    }

    @Test
    void deletingDropsTheFolderAndItsChildrenOnly() {
        Map<String, IsolationLevel> after = ConnectionGroupIsolation.deleted(
            levels("Lab", IsolationLevel.SANDBOX, "Lab/DB", IsolationLevel.PROCESS, "Lab2", IsolationLevel.NONE), "Lab");
        assertThat(after).containsExactly("Lab2", IsolationLevel.NONE);
    }

    @Test
    void inheritedNamesTheFolderThatSetsIt() {
        ConnectionGroupIsolation.Inherited inherited = ConnectionGroupIsolation.inherited(
            " Customers / ACME ", levels("Customers", IsolationLevel.SANDBOX)::get);
        assertThat(inherited.level()).isEqualTo(IsolationLevel.SANDBOX);
        assertThat(inherited.groupPath()).isEqualTo("Customers");
        assertThat(ConnectionGroupIsolation.inherited(null, levels()::get)).isNull();
        assertThat(ConnectionGroupIsolation.inheritedFromAbove("Customers", levels("Customers", IsolationLevel.SANDBOX)::get))
            .isNull();
    }
}
